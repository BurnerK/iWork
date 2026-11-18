package io.iwork.conversation;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import io.iwork.model.ModelAuthorization;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

@Service
@org.springframework.core.annotation.Order(0)
public class TaskService implements ApplicationRunner {
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
    private final ConcurrentMap<String, Thread> workers = new ConcurrentHashMap<>();
    private volatile boolean stopping;

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

    public record PublishedVersion(String versionId, String title, int versionNumber) {}

    public record Message(long id, String taskId, String role, String content, Instant createdAt) {}

    public record Page<T>(List<T> items, boolean hasMore) {}

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
        db.update(
                "UPDATE tasks SET status='INTERRUPTED',execution_state='UNKNOWN',error_code='process_restarted',updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING'");
        db.update(
                "UPDATE tasks SET execution_state='UNKNOWN' WHERE execution_state IN ('RUNNING','STOP_REQUESTED')");
        db.queryForList("SELECT task_id FROM document_drafts", String.class)
                .forEach(documents::cleanup);
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

    public Page<Task> list(Caller caller, String conversationId, int page) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        conversation(caller, conversationId, false);
        var rows =
                query(
                        true,
                        "tasks.owner_id=? AND tasks.conversation_id=? ORDER BY tasks.created_at DESC,tasks.id DESC LIMIT 21 OFFSET ?",
                        caller.id(),
                        conversationId,
                        page * 20);
        return new Page<>(rows.stream().limit(20).toList(), rows.size() > 20);
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
                                        r.getTimestamp("created_at").toInstant()),
                        conversationId,
                        after);
        return new Page<>(rows.stream().limit(100).toList(), rows.size() > 100);
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
        Task accepted;
        try {
            accepted =
                    tx.execute(
                            status -> {
                                var live = identity.verify(caller, true);
                                if (live.mustChangePassword())
                                    throw new ApiFailure(403, "password_change_required");
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
                                materialIds.forEach(id -> materials.get(caller, id));
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
                                        (project == null
                                                        ? ""
                                                        : "项目工作背景：\n"
                                                                + project.instructions()
                                                                + "\n")
                                                + (config == null
                                                        ? ""
                                                        : "智能体处理方式：\n" + config.instructions());
                                models.require(caller.id(), selectedModel);
                                if (!query("conversation_id=? AND status='RUNNING'", conversationId)
                                        .isEmpty()) throw new ApiFailure(409, "conversation_busy");
                                if (!capacity.tryAcquire())
                                    throw new ApiFailure(429, "task_capacity_exceeded");
                                acquired[0] = true;
                                String id = UUID.randomUUID().toString();
                                db.update(
                                        "INSERT INTO tasks(id,owner_id,conversation_id,model_id,request_id,request_hash,status,execution_state,output_mode,base_version_id,agent_id,agent_name,agent_instructions,project_id,project_name,project_instructions) VALUES(?,?,?,?,?,?,'RUNNING','RUNNING',?,?,?,?,?,?,?,?)",
                                        id,
                                        caller.id(),
                                        conversationId,
                                        selectedModel,
                                        requestId,
                                        hash,
                                        outputMode,
                                        baseVersionId,
                                        agentId,
                                        config == null ? null : config.name(),
                                        config == null ? "" : config.instructions(),
                                        projectId,
                                        project == null ? null : project.name(),
                                        project == null ? "" : project.instructions());
                                for (int i = 0; i < materialIds.size(); i++)
                                    db.update(
                                            "INSERT INTO task_materials(task_id,material_id,position) VALUES(?,?,?)",
                                            id,
                                            materialIds.get(i),
                                            i);
                                db.update(
                                        "INSERT INTO messages(task_id,conversation_id,role,content) VALUES(?,?,'user',?)",
                                        id,
                                        conversationId,
                                        message);
                                db.update(
                                        "UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                                        conversationId);
                                return get(caller, id);
                            });
        } catch (RuntimeException failure) {
            if (acquired[0]) capacity.release();
            throw failure;
        }
        if (acquired[0]) {
            var input =
                    new TaskRunner.Input(
                            accepted.id(),
                            conversationId,
                            caller,
                            accepted.modelId(),
                            message,
                            outputMode,
                            initializedInstructions[0]);
            Thread worker = Thread.ofVirtual().unstarted(() -> execute(input));
            workers.put(accepted.id(), worker);
            worker.start();
        }
        return accepted;
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
                            db.update(
                                    "UPDATE tasks SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING'",
                                    stopping ? "INTERRUPTED" : "FAILED",
                                    stopping ? "process_restarted" : errorCode,
                                    input.taskId()));
        } finally {
            Thread.interrupted();
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
        if (reply == null || reply.isBlank() || reply.length() > 200000)
            throw new IllegalStateException("Invalid execution result");
        var draft = input.outputMode().equals("DOCUMENT") ? documents.prepareFinal(input) : null;
        tx.executeWithoutResult(
                status -> {
                    identity.verify(input.caller(), true);
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
            } catch (org.springframework.dao.DataAccessException unavailable) {
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
        var cancelled =
                tx.execute(
                        status -> {
                            identity.verify(caller, true);
                            var rows = query("id=? AND owner_id=? FOR UPDATE", id, caller.id());
                            if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
                            db.update(
                                    "UPDATE tasks SET status='CANCELLED',execution_state='STOP_REQUESTED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=? AND status='RUNNING'",
                                    id,
                                    caller.id());
                            return get(caller, id);
                        });
        if (cancelled.status().equals("CANCELLED")) {
            var worker = workers.get(id);
            if (worker != null) worker.interrupt();
        }
        return cancelled;
    }

    @PreDestroy
    void close() {
        stopping = true;
        workers.values().forEach(Thread::interrupt);
    }
}
