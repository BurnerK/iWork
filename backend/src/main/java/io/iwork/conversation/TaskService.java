package io.iwork.conversation;

import io.iwork.artifacts.DocumentService.PublishedVersion;
import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import io.iwork.model.ModelAuthorization;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Service
@org.springframework.core.annotation.Order(0)
public class TaskService implements ApplicationRunner {
    private static final int MAX_REPLY_LENGTH = 200000;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;
    private final ModelAuthorization models;
    private final io.iwork.artifacts.MaterialService materials;
    private final ObjectProvider<TaskRunner> runners;
    private final io.iwork.artifacts.DocumentService documents;
    private final io.iwork.agent.PersonalAgentService agents;
    private final ProjectService projects;
    private final Semaphore capacity;
    private final ScheduledExecutorService dispatcher =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("task-queue").factory());
    private final ConcurrentMap<String, Thread> workers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LiveReply> liveReplies = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    record ReplySnapshot(int version, String content) {}

    private static final class LiveReply {
        private final Sinks.Many<ReplySnapshot> updates = Sinks.many().replay().latest();
        private final StringBuilder content = new StringBuilder();
        private String replyId;
        private int version;
        private boolean finished;

        LiveReply() {
            updates.tryEmitNext(new ReplySnapshot(0, ""));
        }

        synchronized void append(String id, String delta) {
            if (finished || delta.isEmpty()) return;
            if (!Objects.equals(id, replyId)) {
                replyId = id;
                content.setLength(0);
            }
            int length = Math.min(delta.length(), MAX_REPLY_LENGTH - content.length());
            if (length == 0) return;
            content.append(delta, 0, length);
            updates.tryEmitNext(new ReplySnapshot(++version, content.toString()));
        }

        synchronized void finish() {
            finished = true;
            updates.tryEmitComplete();
        }
    }

    Flux<ReplySnapshot> replyStream(Caller caller, String id) {
        var task = get(caller, id);
        var live = liveReplies.get(id);
        if (!task.status().equals("RUNNING") || !task.outputMode().equals("TEXT") || live == null)
            return Flux.empty();
        return live.updates
                .asFlux()
                .sample(Duration.ofMillis(50))
                .onBackpressureLatest()
                .publishOn(Schedulers.boundedElastic(), 1)
                .takeWhile(
                        snapshot -> {
                            try {
                                return !identity.verify(caller, false).mustChangePassword();
                            } catch (ApiFailure revoked) {
                                return false;
                            }
                        });
    }

    private void finishReply(String id) {
        var live = liveReplies.remove(id);
        if (live != null) live.finish();
    }

    public TaskService(
            JdbcTemplate db,
            TransactionTemplate tx,
            IdentityService identity,
            ModelAuthorization models,
            io.iwork.artifacts.MaterialService materials,
            io.iwork.artifacts.DocumentService documents,
            io.iwork.agent.PersonalAgentService agents,
            ProjectService projects,
            ObjectProvider<TaskRunner> runners,
            @Value("${iwork.task-capacity:2}") int capacity) {
        if (capacity < 1 || capacity > 16)
            throw new IllegalArgumentException("Task capacity must be 1 to 16");
        this.projects = projects;
        this.agents = agents;
        this.documents = documents;
        this.db = db;
        this.tx = tx;
        this.identity = identity;
        this.models = models;
        this.materials = materials;
        this.runners = runners;
        this.capacity = new Semaphore(capacity);
    }

    public record Task(
            String id,
            String conversationId,
            String modelId,
            String requestId,
            String status,
            String errorCode,
            String executionState,
            Instant createdAt,
            Instant updatedAt,
            String outputMode,
            String baseVersionId,
            String agentId,
            String agentName,
            String projectId,
            String projectName,
            PublishedVersion publishedVersion) {}

    public record Message(
            long id,
            String taskId,
            String role,
            String content,
            Instant createdAt,
            PublishedVersion publishedVersion) {}

    public record Page<T>(List<T> items, boolean hasMore) {}

    public record TaskPage(
            List<Task> items,
            boolean hasMore,
            int queuedCount,
            boolean queuePaused,
            String confirmedRequestId) {}

    private record QueueHead(String taskId, String conversationId, Caller caller) {}

    private List<Task> query(String condition, Object... args) {
        return query(false, condition, args);
    }

    private List<Task> query(boolean withVersions, String condition, Object... args) {
        String select =
                withVersions
                        ? "SELECT tasks.*, v.id AS published_id, v.title AS published_title, v.version_number AS published_number "
                                + "FROM tasks LEFT JOIN document_versions v ON v.task_id=tasks.id "
                        : "SELECT tasks.* FROM tasks ";
        return db.query(
                select + "WHERE " + condition,
                (r, n) ->
                        new Task(
                                r.getString("id"),
                                r.getString("conversation_id"),
                                r.getString("model_id"),
                                r.getString("request_id"),
                                r.getString("status"),
                                r.getString("error_code"),
                                r.getString("execution_state"),
                                r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("updated_at").toInstant(),
                                r.getString("output_mode"),
                                r.getString("base_version_id"),
                                r.getString("agent_id"),
                                r.getString("agent_name"),
                                r.getString("project_id"),
                                r.getString("project_name"),
                                !withVersions || r.getString("published_id") == null
                                        ? null
                                        : new PublishedVersion(
                                                r.getString("published_id"),
                                                r.getString("published_title"),
                                                r.getInt("published_number"))),
                args);
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(
                status -> {
                    db.update(
                            """
                    UPDATE conversations c SET queue_paused=TRUE
                    WHERE EXISTS (SELECT 1 FROM tasks t WHERE t.conversation_id=c.id AND t.status='RUNNING')
                      AND EXISTS (SELECT 1 FROM tasks t WHERE t.conversation_id=c.id AND t.status='QUEUED')
                    """);
                    db.update(
                            "UPDATE tasks SET status='INTERRUPTED',execution_state='UNKNOWN',error_code='process_restarted',updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING'");
                });
        db.update(
                "UPDATE tasks SET execution_state='UNKNOWN' WHERE execution_state IN ('RUNNING','STOP_REQUESTED')");
        db.queryForList("SELECT task_id FROM document_drafts", String.class)
                .forEach(documents::cleanup);
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 250, TimeUnit.MILLISECONDS);
    }

    public Task get(Caller caller, String id) {
        var rows = query("id=? AND owner_id=?", id, caller.id());
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        return rows.getFirst();
    }

    private void conversation(Caller caller, String id, boolean lock) {
        var rows =
                db.queryForList(
                        "SELECT id FROM conversations WHERE id=? AND owner_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        String.class,
                        id,
                        caller.id());
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
    }

    public TaskPage list(Caller caller, String conversationId, int page, String requestId) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        return tx.execute(
                status -> {
                    conversation(caller, conversationId, false);
                    var rows =
                            query(
                                    true,
                                    "tasks.owner_id=? AND tasks.conversation_id=? AND tasks.status<>'QUEUED' ORDER BY tasks.created_at DESC,tasks.id DESC LIMIT 21 OFFSET ?",
                                    caller.id(),
                                    conversationId,
                                    page * 20);
                    int count = queuedCount(conversationId);
                    String confirmed = null;
                    if (requestId != null && requestId.matches("[A-Za-z0-9_-]{1,100}")) {
                        confirmed =
                                db
                                        .queryForList(
                                                """
                                SELECT request_id FROM tasks WHERE owner_id=? AND request_id=? AND conversation_id=?
                                """,
                                                String.class,
                                                caller.id(),
                                                requestId,
                                                conversationId)
                                        .stream()
                                        .findFirst()
                                        .orElse(null);
                    }
                    return new TaskPage(
                            rows.stream().limit(20).toList(),
                            rows.size() > 20,
                            count,
                            count > 0 && queuePaused(conversationId),
                            confirmed);
                });
    }

    private int queuedCount(String conversationId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE conversation_id=? AND status='QUEUED'",
                Integer.class,
                conversationId);
    }

    private boolean queuePaused(String conversationId) {
        return db.queryForObject(
                "SELECT queue_paused FROM conversations WHERE id=?", Boolean.class, conversationId);
    }

    private void pauseQueue(String conversationId) {
        db.update(
                """
                UPDATE conversations SET queue_paused=TRUE WHERE id=?
                AND EXISTS (SELECT 1 FROM tasks WHERE conversation_id=? AND status='QUEUED')
                """,
                conversationId,
                conversationId);
    }

    public Page<Message> messages(Caller caller, String conversationId, long after) {
        if (after < 0) throw new ApiFailure(400, "invalid_pagination");
        conversation(caller, conversationId, false);
        var rows =
                db.query(
                        "SELECT * FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT 101",
                        (r, n) ->
                                new Message(
                                        r.getLong("id"),
                                        r.getString("task_id"),
                                        r.getString("role"),
                                        r.getString("content"),
                                        r.getTimestamp("created_at").toInstant(),
                                        null),
                        conversationId,
                        after);
        var page = rows.stream().limit(100).toList();
        var versions =
                documents.publishedVersions(
                        caller,
                        page.stream()
                                .filter(message -> message.role().equals("assistant"))
                                .map(Message::taskId)
                                .toList());
        return new Page<>(
                page.stream()
                        .map(
                                message -> {
                                    var version = versions.get(message.taskId());
                                    return version == null || !message.role().equals("assistant")
                                            ? message
                                            : new Message(
                                                    message.id(),
                                                    message.taskId(),
                                                    message.role(),
                                                    message.content(),
                                                    message.createdAt(),
                                                    version);
                                })
                        .toList(),
                rows.size() > 100);
    }

    public Task submit(
            Caller caller,
            String conversationId,
            String requestId,
            String modelId,
            String message,
            List<String> materialIds) {
        return submit(caller, conversationId, requestId, modelId, message, materialIds, "TEXT");
    }

    public Task submit(
            Caller caller,
            String conversationId,
            String requestId,
            String modelId,
            String message,
            List<String> materialIds,
            String outputMode) {
        return submit(
                caller, conversationId, requestId, modelId, message, materialIds, outputMode, null);
    }

    public Task submit(
            Caller caller,
            String conversationId,
            String requestId,
            String modelId,
            String message,
            List<String> materialIds,
            String outputMode,
            String baseVersionId) {
        return submit(
                caller,
                conversationId,
                requestId,
                modelId,
                message,
                materialIds,
                outputMode,
                baseVersionId,
                null);
    }

    public Task submit(
            Caller caller,
            String conversationId,
            String requestId,
            String modelId,
            String message,
            List<String> materialIds,
            String outputMode,
            String baseVersionId,
            String agentId) {
        return submit(
                caller,
                conversationId,
                requestId,
                modelId,
                message,
                materialIds,
                outputMode,
                baseVersionId,
                agentId,
                false);
    }

    public Task submit(
            Caller caller,
            String conversationId,
            String requestId,
            String modelId,
            String message,
            List<String> materialIds,
            String outputMode,
            String baseVersionId,
            String agentId,
            boolean allowQueue) {
        if (agentId != null && (!agentId.matches("[a-f0-9-]{36}") || modelId != null))
            throw new ApiFailure(400, "invalid_task");
        if (baseVersionId != null
                && (!outputMode.equals("DOCUMENT")
                        || !baseVersionId.matches(
                                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            throw new ApiFailure(400, "invalid_task");
        if (!Set.of("TEXT", "DOCUMENT").contains(outputMode))
            throw new ApiFailure(400, "invalid_task");
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{1,100}")
                || agentId == null && (modelId == null || !modelId.matches("[a-fA-F0-9-]{36}"))
                || message == null
                || message.isBlank()
                || message.length() > 20000) throw new ApiFailure(400, "invalid_task");
        if (materialIds.size() > 5
                || new HashSet<>(materialIds).size() != materialIds.size()
                || materialIds.stream()
                        .anyMatch(
                                id ->
                                        !id.matches(
                                                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            throw new ApiFailure(400, "invalid_task");
        final String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    ((agentId == null
                                                                            ? ""
                                                                            : "agent-v1\n"
                                                                                    + agentId
                                                                                    + "\n")
                                                                    + (baseVersionId == null
                                                                            ? ""
                                                                            : "base-version-v1\n"
                                                                                    + baseVersionId
                                                                                    + "\n")
                                                                    + (outputMode.equals("TEXT")
                                                                            ? ""
                                                                            : "document-v1\n")
                                                                    + (materialIds.isEmpty()
                                                                            ? conversationId
                                                                                    + "\n"
                                                                                    + modelId
                                                                                    + "\n"
                                                                                    + message
                                                                            : "materials-v1\n"
                                                                                    + conversationId
                                                                                    + "\n"
                                                                                    + modelId
                                                                                    + "\n"
                                                                                    + message
                                                                                            .length()
                                                                                    + ":"
                                                                                    + message
                                                                                    + "\n"
                                                                                    + String.join(
                                                                                            ",",
                                                                                            materialIds)))
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        boolean[] acquired = {false};
        String[] initializedInstructions = {""};
        String id = UUID.randomUUID().toString();
        var replyBuffer = new LiveReply();
        Task accepted;
        try {
            accepted =
                    tx.execute(
                            status -> {
                                verifyExecutionCaller(caller);
                                conversation(caller, conversationId, true);
                                var existing =
                                        query(
                                                "owner_id=? AND request_id=?",
                                                caller.id(),
                                                requestId);
                                if (!existing.isEmpty()) {
                                    var old = existing.getFirst();
                                    var oldHash =
                                            db.queryForObject(
                                                    "SELECT request_hash FROM tasks WHERE id=?",
                                                    String.class,
                                                    old.id());
                                    if (!hash.equals(oldHash))
                                        throw new ApiFailure(409, "idempotency_conflict");
                                    return old;
                                }
                                materialIds.forEach(
                                        materialId -> materials.get(caller, materialId));
                                if (baseVersionId != null) documents.version(caller, baseVersionId);
                                if (runners.getIfAvailable() == null || stopping)
                                    throw new ApiFailure(503, "execution_unavailable");
                                var config = agentId == null ? null : agents.get(caller, agentId);
                                String selectedModel = config == null ? modelId : config.modelId();
                                String projectId =
                                        db.queryForObject(
                                                "SELECT project_id FROM conversations WHERE id=? AND owner_id=?",
                                                String.class,
                                                conversationId,
                                                caller.id());
                                var project =
                                        projectId == null ? null : projects.get(caller, projectId);
                                initializedInstructions[0] =
                                        instructions(
                                                project == null ? null : project.instructions(),
                                                config == null ? null : config.instructions());
                                models.require(caller.id(), selectedModel);
                                boolean busy =
                                        queuedCount(conversationId) > 0
                                                || hasActiveTask(conversationId, allowQueue);
                                if (busy && !allowQueue)
                                    throw new ApiFailure(409, "conversation_busy");
                                if (!busy && !capacity.tryAcquire())
                                    throw new ApiFailure(429, "task_capacity_exceeded");
                                acquired[0] = !busy;
                                db.update(
                                        """
                                        INSERT INTO tasks(id,owner_id,conversation_id,model_id,request_id,request_hash,
                                            status,execution_state,output_mode,base_version_id,agent_id,agent_name,agent_instructions,
                                            project_id,project_name,project_instructions,queued_message,queued_auth_version)
                                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                                        """,
                                        id,
                                        caller.id(),
                                        conversationId,
                                        selectedModel,
                                        requestId,
                                        hash,
                                        busy ? "QUEUED" : "RUNNING",
                                        busy ? "WAITING" : "RUNNING",
                                        outputMode,
                                        baseVersionId,
                                        agentId,
                                        config == null ? null : config.name(),
                                        config == null ? null : config.instructions(),
                                        projectId,
                                        project == null ? null : project.name(),
                                        project == null ? null : project.instructions(),
                                        busy ? message : null,
                                        busy ? caller.authVersion() : null);
                                for (int i = 0; i < materialIds.size(); i++)
                                    db.update(
                                            "INSERT INTO task_materials(task_id,material_id,position) VALUES(?,?,?)",
                                            id,
                                            materialIds.get(i),
                                            i);
                                if (!busy) recordSend(id, conversationId, message, replyBuffer);
                                return get(caller, id);
                            });
        } catch (RuntimeException failure) {
            finishReply(id);
            if (acquired[0]) capacity.release();
            throw failure;
        }
        if (acquired[0]) launch(accepted, caller, message, initializedInstructions[0], replyBuffer);
        return accepted;
    }

    private Caller verifyExecutionCaller(Caller caller) {
        var live = identity.verify(caller, true);
        if (live.mustChangePassword()) throw new ApiFailure(403, "password_change_required");
        return live;
    }

    private static String instructions(String project, String agent) {
        return (project == null ? "" : "项目工作背景：\n" + project + "\n")
                + (agent == null ? "" : "智能体处理方式：\n" + agent);
    }

    private boolean hasActiveTask(String conversationId, boolean includeStopping) {
        return db.queryForObject(
                """
                SELECT EXISTS (SELECT 1 FROM tasks WHERE conversation_id=?
                    AND (status='RUNNING' OR (? AND execution_state IN ('RUNNING','STOP_REQUESTED'))))
                """,
                Boolean.class,
                conversationId,
                includeStopping);
    }

    private void recordSend(
            String id, String conversationId, String message, LiveReply replyBuffer) {
        db.update(
                "INSERT INTO messages(task_id,conversation_id,role,content) VALUES(?,?,'user',?)",
                id,
                conversationId,
                message);
        db.update(
                "UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                conversationId);
        if (replyBuffer != null) liveReplies.put(id, replyBuffer);
    }

    private void launch(
            Task task, Caller caller, String message, String instructions, LiveReply replyBuffer) {
        var input =
                new TaskRunner.Input(
                        task.id(),
                        task.conversationId(),
                        caller,
                        task.modelId(),
                        message,
                        task.outputMode(),
                        instructions,
                        replyBuffer::append);
        launch(input);
    }

    private void launch(TaskRunner.Input input) {
        Thread worker = Thread.ofVirtual().unstarted(() -> execute(input));
        workers.put(input.taskId(), worker);
        worker.start();
    }

    private void dispatch() {
        if (stopping || capacity.availablePermits() == 0 || runners.getIfAvailable() == null)
            return;
        try {
            var heads =
                    db.query(
                            """
                    SELECT t.id,t.owner_id,t.conversation_id,t.queued_auth_version
                    FROM tasks t JOIN conversations c ON c.id=t.conversation_id
                    WHERE t.status='QUEUED' AND NOT c.queue_paused
                      AND NOT EXISTS (SELECT 1 FROM tasks active
                          WHERE active.conversation_id=t.conversation_id
                            AND active.execution_state IN ('RUNNING','STOP_REQUESTED'))
                      AND NOT EXISTS (SELECT 1 FROM tasks older
                          WHERE older.conversation_id=t.conversation_id AND older.status='QUEUED'
                            AND older.queue_order<t.queue_order)
                    ORDER BY t.queue_order LIMIT 16
                    """,
                            (r, n) ->
                                    new QueueHead(
                                            r.getString("id"),
                                            r.getString("conversation_id"),
                                            new Caller(
                                                    r.getString("owner_id"),
                                                    null,
                                                    null,
                                                    null,
                                                    false,
                                                    r.getLong("queued_auth_version"))));
            for (var head : heads) {
                if (stopping || !capacity.tryAcquire()) break;
                dispatch(head);
            }
        } catch (RuntimeException failure) {
            if (!stopping)
                org.slf4j.LoggerFactory.getLogger(TaskService.class)
                        .warn("Task queue dispatch failed", failure);
        }
    }

    private void dispatch(QueueHead head) {
        var replyBuffer = new LiveReply();
        TaskRunner.Input input = null;
        try {
            input = tx.execute(status -> promote(head, replyBuffer));
            if (input != null) launch(input);
        } finally {
            if (input == null) {
                finishReply(head.taskId());
                capacity.release();
            }
        }
    }

    private TaskRunner.Input promote(QueueHead head, LiveReply replyBuffer) {
        Caller live = null;
        String error = null;
        try {
            live = verifyExecutionCaller(head.caller());
        } catch (ApiFailure denied) {
            error = denied.getMessage();
        }
        String cid = head.conversationId();
        conversation(head.caller(), cid, true);
        var task = get(head.caller(), head.taskId());
        if (!task.status().equals("QUEUED") || queuePaused(cid) || hasActiveTask(cid, true))
            return null;
        var stored =
                db.queryForMap(
                        """
                        SELECT queued_message,project_instructions,agent_instructions
                        FROM tasks WHERE id=?
                        """,
                        task.id());
        String message = (String) stored.get("queued_message");
        if (error == null) {
            try {
                models.require(live.id(), task.modelId());
                if (task.agentId() != null) agents.get(live, task.agentId());
                if (task.projectId() != null) {
                    projects.get(live, task.projectId());
                } else if (stored.get("project_instructions") != null) {
                    // Project deletion clears the foreign key, not the
                    // snapshot.
                    throw new ApiFailure(404, "not_found");
                }
                if (task.baseVersionId() != null) documents.version(live, task.baseVersionId());
                materials(live, task.id());
            } catch (ApiFailure denied) {
                error = denied.getMessage();
            }
        }
        db.update(
                """
                        UPDATE tasks SET status=?,execution_state=?,error_code=?,queued_message=NULL,
                            queued_auth_version=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                        """,
                error == null ? "RUNNING" : "FAILED",
                error == null ? "RUNNING" : "STOPPED",
                error,
                task.id());
        recordSend(task.id(), cid, message, error == null ? replyBuffer : null);
        if (error != null) {
            pauseQueue(cid);
            return null;
        }
        return new TaskRunner.Input(
                task.id(),
                cid,
                live,
                task.modelId(),
                message,
                task.outputMode(),
                instructions(
                        (String) stored.get("project_instructions"),
                        (String) stored.get("agent_instructions")),
                replyBuffer::append);
    }

    private void execute(TaskRunner.Input input) {
        try {
            if (!get(input.caller(), input.taskId()).status().equals("RUNNING") || stopping) return;
            String reply = runners.getObject().execute(input);
            acceptResult(input, reply);
        } catch (Exception failure) {
            Thread.interrupted();
            String errorCode = executionError(failure);
            persist(
                    () ->
                            tx.executeWithoutResult(
                                    status -> {
                                        conversation(input.caller(), input.conversationId(), true);
                                        if (db.update(
                                                        "UPDATE tasks SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING'",
                                                        stopping ? "INTERRUPTED" : "FAILED",
                                                        stopping ? "process_restarted" : errorCode,
                                                        input.taskId())
                                                > 0) pauseQueue(input.conversationId());
                                    }));
        } finally {
            Thread.interrupted();
            finishReply(input.taskId());
            try {
                persist(
                        () ->
                                db.update(
                                        "UPDATE tasks SET execution_state='STOPPED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                                        input.taskId()));
            } finally {
                try {
                    documents.cleanup(input.taskId());
                } finally {
                    workers.remove(input.taskId());
                    capacity.release();
                }
            }
        }
    }

    void acceptResult(TaskRunner.Input input, String reply) {
        if (reply == null || reply.isBlank() || reply.length() > MAX_REPLY_LENGTH)
            throw new IllegalStateException("Invalid execution result");
        var draft = input.outputMode().equals("DOCUMENT") ? documents.prepareFinal(input) : null;
        tx.executeWithoutResult(
                status -> {
                    identity.verify(input.caller(), true);
                    conversation(input.caller(), input.conversationId(), true);
                    var current = query("id=? FOR UPDATE", input.taskId()).getFirst();
                    if (!current.status().equals("RUNNING")) return;
                    if (draft != null) documents.publish(input, draft);
                    db.update(
                            "INSERT INTO messages(task_id,conversation_id,role,content) VALUES(?,?,'assistant',?)",
                            input.taskId(),
                            input.conversationId(),
                            reply);
                    db.update(
                            "UPDATE tasks SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            input.taskId());
                    db.update(
                            "UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            input.conversationId());
                });
    }

    private static String executionError(Throwable failure) {
        for (int i = 0; i < 10 && failure != null; i++, failure = failure.getCause())
            if (failure instanceof ApiFailure) return failure.getMessage();
        return "execution_failed";
    }

    private void persist(Runnable write) {
        while (true) {
            try {
                write.run();
                return;
            } catch (org.springframework.dao.DataAccessException
                    | org.springframework.transaction.TransactionException unavailable) {
                if (stopping) return;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    if (stopping) return;
                }
            }
        }
    }

    public List<io.iwork.artifacts.MaterialService.Material> materials(
            Caller caller, String taskId) {
        get(caller, taskId);
        return db
                .queryForList(
                        "SELECT material_id FROM task_materials WHERE task_id=? ORDER BY position",
                        String.class,
                        taskId)
                .stream()
                .map(id -> materials.get(caller, id))
                .toList();
    }

    public Task cancel(Caller caller, String id) {
        return cancel(caller, id, false);
    }

    public Task cancel(Caller caller, String id, boolean pausePending) {
        var cancelled =
                tx.execute(
                        status -> {
                            identity.verify(caller, true);
                            var task = get(caller, id);
                            conversation(caller, task.conversationId(), true);
                            int changed =
                                    db.update(
                                            "UPDATE tasks SET status='CANCELLED',execution_state='STOP_REQUESTED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=? AND status='RUNNING'",
                                            id,
                                            caller.id());
                            if (changed > 0 || pausePending) pauseQueue(task.conversationId());
                            return query("id=? FOR UPDATE", id).getFirst();
                        });
        if (cancelled.status().equals("CANCELLED")) {
            finishReply(id);
            var worker = workers.get(id);
            if (worker != null) worker.interrupt();
        }
        return cancelled;
    }

    @PreDestroy
    void close() {
        stopping = true;
        dispatcher.shutdownNow();
        workers.values().forEach(Thread::interrupt);
    }
}
