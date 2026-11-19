package io.iwork.artifacts;

import io.agentscope.core.tool.*;
import io.iwork.conversation.TaskRunner;
import io.iwork.identity.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentService {
    public static final int MAX_BYTES = 256 * 1024;
    private final Path workspaces, documents;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;

    public DocumentService(
            @Value("${iwork.files-directory}") Path root,
            JdbcTemplate db,
            TransactionTemplate tx,
            IdentityService identity)
            throws IOException {
        workspaces = root.resolve("document-workspaces").toAbsolutePath();
        documents = root.resolve("documents").toAbsolutePath();
        Files.createDirectories(workspaces);
        Files.createDirectories(documents);
        this.db = db;
        this.tx = tx;
        this.identity = identity;
    }

    public record Draft(
            String id,
            String taskId,
            String artifactId,
            String versionId,
            String title,
            long byteSize,
            String sha256) {}

    public record PublishedVersion(String versionId, String title, int versionNumber) {}

    public Map<String, PublishedVersion> publishedVersions(Caller caller, List<String> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        return new NamedParameterJdbcTemplate(db)
                .query(
                        """
                        SELECT v.task_id, v.id, v.title, v.version_number
                        FROM document_versions v JOIN documents d ON d.id=v.document_id
                        WHERE d.owner_id=:owner AND v.task_id IN (:tasks)
                        """,
                        Map.of("owner", caller.id(), "tasks", taskIds),
                        rows -> {
                            var versions = new HashMap<String, PublishedVersion>();
                            while (rows.next()) {
                                versions.put(
                                        rows.getString("task_id"),
                                        new PublishedVersion(
                                                rows.getString("id"),
                                                rows.getString("title"),
                                                rows.getInt("version_number")));
                            }
                            return versions;
                        });
    }

    private List<Draft> drafts(String task) {
        return db.query(
                "SELECT * FROM document_drafts WHERE task_id=?",
                (r, n) ->
                        new Draft(
                                r.getString("id"),
                                r.getString("task_id"),
                                r.getString("artifact_id"),
                                r.getString("version_id"),
                                r.getString("title"),
                                r.getLong("byte_size"),
                                r.getString("sha256")),
                task);
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void authorize(TaskRunner.Input input) {
        identity.verify(input.caller(), true);
        var rows =
                db.queryForList(
                        "SELECT status,output_mode FROM tasks WHERE id=? AND owner_id=? AND conversation_id=? FOR UPDATE",
                        input.taskId(),
                        input.caller().id(),
                        input.conversationId());
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        if (!rows.getFirst().get("status").equals("RUNNING"))
            throw new ApiFailure(409, "task_not_running");
        if (!rows.getFirst().get("output_mode").equals("DOCUMENT"))
            throw new ApiFailure(403, "document_not_authorized");
    }

    private void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), ".document-", ".part");
        try {
            Files.write(pending, bytes);
            try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
            try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                channel.force(true);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private byte[] read(Path path, long size, String hash) {
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length != size || !checksum(bytes).equals(hash))
                throw new ApiFailure(503, "document_storage_unavailable");
            return bytes;
        } catch (IOException failure) {
            throw new ApiFailure(503, "document_storage_unavailable");
        }
    }

    private Map<String, Object> prepare(TaskRunner.Input input, String title, String markdown) {
        if (title == null
                || title.isBlank()
                || title.length() > 120
                || title.contains("/")
                || title.contains("\\")
                || title.chars().anyMatch(Character::isISOControl)
                || markdown == null
                || markdown.isBlank()
                || markdown.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(markdown))
            throw new ApiFailure(400, "invalid_document");
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new ApiFailure(413, "document_too_large");
        tx.executeWithoutResult(status -> authorize(input));
        String base = baseVersion(input);
        String artifact =
                base == null
                        ? UUID.randomUUID().toString()
                        : (String) version(input.caller(), base).get("id");
        var draft =
                new Draft(
                        UUID.randomUUID().toString(),
                        input.taskId(),
                        artifact,
                        UUID.randomUUID().toString(),
                        title,
                        bytes.length,
                        checksum(bytes));
        try {
            write(workspaces.resolve(input.taskId()).resolve(draft.id() + ".md"), bytes);
        } catch (IOException failure) {
            throw new ApiFailure(503, "document_storage_unavailable");
        }
        var accepted =
                tx.execute(
                        status -> {
                            authorize(input);
                            var old = drafts(input.taskId());
                            if (!old.isEmpty()) {
                                if (!old.getFirst().sha256().equals(draft.sha256())
                                        || !old.getFirst().title().equals(title))
                                    throw new ApiFailure(409, "document_already_prepared");
                                return old.getFirst();
                            }
                            db.update(
                                    "INSERT INTO document_drafts(id,task_id,artifact_id,version_id,title,byte_size,sha256) VALUES(?,?,?,?,?,?,?)",
                                    draft.id(),
                                    draft.taskId(),
                                    draft.artifactId(),
                                    draft.versionId(),
                                    draft.title(),
                                    draft.byteSize(),
                                    draft.sha256());
                            return draft;
                        });
        return Map.of(
                "draftId",
                accepted.id(),
                "state",
                "PREPARED",
                "message",
                "正文已准备；只有业务任务完成提交后才是正式成果。");
    }

    public Draft prepareFinal(TaskRunner.Input input) {
        var draft =
                tx.execute(
                        status -> {
                            authorize(input);
                            var rows = drafts(input.taskId());
                            if (rows.isEmpty()) throw new ApiFailure(422, "document_not_generated");
                            return rows.getFirst();
                        });
        byte[] bytes =
                read(
                        workspaces.resolve(input.taskId()).resolve(draft.id() + ".md"),
                        draft.byteSize(),
                        draft.sha256());
        var target = documents.resolve(draft.versionId() + ".md");
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                read(target, draft.byteSize(), draft.sha256());
            else write(target, bytes);
        } catch (IOException failure) {
            throw new ApiFailure(503, "document_storage_unavailable");
        }
        return draft;
    }

    /** Caller holds the task row lock; this participates in the reply/completion transaction. */
    public void publish(TaskRunner.Input input, Draft draft) {
        if (!draft.taskId().equals(input.taskId()))
            throw new ApiFailure(403, "document_not_authorized");
        String base = baseVersion(input);
        int number = 1;
        if (base == null)
            db.update(
                    "INSERT INTO documents(id,owner_id,conversation_id,title) VALUES(?,?,?,?)",
                    draft.artifactId(),
                    input.caller().id(),
                    input.conversationId(),
                    draft.title());
        else {
            if (!version(input.caller(), base).get("id").equals(draft.artifactId())
                    || db.queryForList(
                                    "SELECT id FROM documents WHERE id=? AND owner_id=? FOR UPDATE",
                                    String.class,
                                    draft.artifactId(),
                                    input.caller().id())
                            .isEmpty()) throw new ApiFailure(403, "document_not_authorized");
            number =
                    db.queryForObject(
                            "SELECT version_number+1 FROM document_versions WHERE document_id=? ORDER BY version_number DESC LIMIT 1 FOR UPDATE",
                            Integer.class,
                            draft.artifactId());
        }
        db.update(
                "INSERT INTO document_versions(id,document_id,task_id,version_number,byte_size,sha256,title,base_version_id) VALUES(?,?,?,?,?,?,?,?)",
                draft.versionId(),
                draft.artifactId(),
                input.taskId(),
                number,
                draft.byteSize(),
                draft.sha256(),
                draft.title(),
                base);
        db.update(
                "INSERT INTO project_documents(project_id,document_id) SELECT t.project_id,? FROM tasks t WHERE t.id=? AND t.project_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM project_documents p WHERE p.project_id=t.project_id AND p.document_id=?)",
                draft.artifactId(),
                input.taskId(),
                draft.artifactId());
    }

    public void cleanup(String task) {
        var directory = workspaces.resolve(task);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(directory)) {
            for (var path : files.toList())
                if (path.getFileName()
                        .toString()
                        .matches("[a-f0-9-]{36}\\.md|\\.document-[a-zA-Z0-9-]+\\.part"))
                    Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                    .warn("Document workspace cleanup pending for task {}", task);
        }
    }

    public final class Tools {
        private final TaskRunner.Input input;

        private boolean sourceRead;

        public Tools(TaskRunner.Input input) {
            this.input = input;
        }

        @Tool(
                name = "prepare_document",
                description =
                        "为本任务准备唯一的 UTF-8 Markdown 正文，最多256KiB。仅准备内容，正式保存由任务完成事务决定；不要把准备成功当作已发布。")
        public Map<String, Object> prepare(
                @ToolParam(name = "title", description = "文档标题，无路径或扩展名") String title,
                @ToolParam(name = "markdown", description = "完整Markdown正文") String markdown) {
            if (baseVersion(input) != null && !sourceRead)
                throw new ApiFailure(409, "document_source_required");
            return DocumentService.this.prepare(input, title, markdown);
        }

        public final class SourceTools {
            @Tool(
                    name = "read_document_version",
                    description = "读取本任务已选定的来源文档版本，每页最多12000字符。按 nextOffset 继续读取，不接受其他版本或路径。")
            public Map<String, Object> read(
                    @ToolParam(name = "offset", description = "字符起点，首次为0") int offset) {
                tx.executeWithoutResult(status -> authorize(input));
                String base = baseVersion(input);
                if (base == null) throw new ApiFailure(403, "document_not_authorized");
                var metadata = version(input.caller(), base);
                String text = new String(content(input.caller(), base), StandardCharsets.UTF_8);
                if (offset < 0
                        || offset > text.length()
                        || offset > 0
                                && offset < text.length()
                                && Character.isLowSurrogate(text.charAt(offset))
                                && Character.isHighSurrogate(text.charAt(offset - 1)))
                    throw new ApiFailure(400, "invalid_offset");
                int end = Math.min(text.length(), offset + 12000);
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                tx.executeWithoutResult(status -> authorize(input));
                if (end > offset) sourceRead = true;
                return Map.of(
                        "versionId",
                        base,
                        "title",
                        metadata.get("title"),
                        "versionNumber",
                        metadata.get("versionNumber"),
                        "markdown",
                        text.substring(offset, end),
                        "nextOffset",
                        end,
                        "hasMore",
                        end < text.length());
            }
        }
    }

    public String baseVersion(TaskRunner.Input input) {
        return db.queryForObject(
                "SELECT base_version_id FROM tasks WHERE id=? AND owner_id=? AND conversation_id=?",
                String.class,
                input.taskId(),
                input.caller().id(),
                input.conversationId());
    }

    private static final String VERSION_COLUMNS =
            "SELECT d.id,v.title,v.id AS versionId,v.version_number AS versionNumber,v.task_id AS taskId,v.byte_size AS byteSize,v.created_at AS createdAt,v.base_version_id AS baseVersionId FROM documents d JOIN document_versions v ON v.document_id=d.id ";

    public io.iwork.conversation.TaskService.Page<Map<String, Object>> list(
            Caller caller, String conversation, int page) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        if (db.queryForList(
                        "SELECT id FROM conversations WHERE id=? AND owner_id=?",
                        String.class,
                        conversation,
                        caller.id())
                .isEmpty()) throw new ApiFailure(404, "not_found");
        var rows =
                db.queryForList(
                        VERSION_COLUMNS
                                + "WHERE d.owner_id=? AND (d.conversation_id=? OR d.id IN (SELECT source.document_id FROM tasks t JOIN document_versions source ON source.id=t.base_version_id WHERE t.owner_id=? AND t.conversation_id=?)) ORDER BY v.created_at DESC,v.id DESC LIMIT 21 OFFSET ?",
                        caller.id(),
                        conversation,
                        caller.id(),
                        conversation,
                        page * 20);
        return new io.iwork.conversation.TaskService.Page<>(
                rows.stream().limit(20).toList(), rows.size() > 20);
    }

    public io.iwork.conversation.TaskService.Page<Map<String, Object>> personal(
            Caller caller, int page) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        var rows =
                db.queryForList(
                        VERSION_COLUMNS
                                + "WHERE d.owner_id=? ORDER BY v.created_at DESC,v.id DESC LIMIT 21 OFFSET ?",
                        caller.id(),
                        page * 20);
        return new io.iwork.conversation.TaskService.Page<>(
                rows.stream().limit(20).toList(), rows.size() > 20);
    }

    public io.iwork.conversation.TaskService.Page<Map<String, Object>> project(
            Caller caller, String project, int page) {
        if (page < 0 || page > 100000) throw new ApiFailure(400, "invalid_pagination");
        if (db.queryForList(
                        "SELECT id FROM projects WHERE id=? AND owner_id=?",
                        String.class,
                        project,
                        caller.id())
                .isEmpty()) throw new ApiFailure(404, "not_found");
        var rows =
                db.queryForList(
                        VERSION_COLUMNS
                                + "JOIN project_documents p ON p.document_id=d.id WHERE p.project_id=? AND d.owner_id=? ORDER BY v.created_at DESC,v.id DESC LIMIT 21 OFFSET ?",
                        project,
                        caller.id(),
                        page * 20);
        return new io.iwork.conversation.TaskService.Page<>(
                rows.stream().limit(20).toList(), rows.size() > 20);
    }

    public Map<String, Object> version(Caller caller, String id) {
        var rows =
                db.queryForList(
                        "SELECT d.id,v.title,v.id AS versionId,v.version_number AS versionNumber,v.task_id AS taskId,v.byte_size AS byteSize,v.sha256,v.base_version_id AS baseVersionId FROM document_versions v JOIN documents d ON d.id=v.document_id WHERE v.id=? AND d.owner_id=?",
                        id,
                        caller.id());
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        return rows.getFirst();
    }

    public byte[] content(Caller caller, String id) {
        var version = version(caller, id);
        return read(
                documents.resolve(version.get("versionId") + ".md"),
                ((Number) version.get("byteSize")).longValue(),
                (String) version.get("sha256"));
    }
}
