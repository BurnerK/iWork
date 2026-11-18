package io.iwork.conversation;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@org.springframework.stereotype.Service
class ConversationService {
    private final JdbcTemplate db;
    private final IdentityService identity;
    private final ProjectService projects;

    ConversationService(JdbcTemplate db, IdentityService identity, ProjectService projects) {
        this.db = db;
        this.identity = identity;
        this.projects = projects;
    }

    record Conversation(
            String id, String title, Instant createdAt, Instant updatedAt, String projectId) {}

    record Page(List<Conversation> items, int page, int size, boolean hasMore) {}

    private List<Conversation> query(String sql, Object... args) {
        return db.query(
                sql,
                (r, n) ->
                        new Conversation(
                                r.getString("id"),
                                r.getString("title"),
                                r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("updated_at").toInstant(),
                                r.getString("project_id")),
                args);
    }

    Page list(Caller caller, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new ApiFailure(400, "invalid_pagination");
        var rows =
                query(
                        "SELECT * FROM conversations WHERE owner_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                        caller.id(),
                        size + 1,
                        page * size);
        return new Page(rows.stream().limit(size).toList(), page, size, rows.size() > size);
    }

    Conversation open(Caller caller, String id) {
        var rows = query("SELECT * FROM conversations WHERE owner_id=? AND id=?", caller.id(), id);
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        return rows.getFirst();
    }

    @Transactional
    Conversation create(Caller caller, Map<String, Object> input) {
        var live = identity.verify(caller, true);
        if (live.mustChangePassword()) throw new ApiFailure(403, "password_change_required");
        if (!java.util.Set.of("title", "projectId").containsAll(input.keySet()))
            throw new ApiFailure(400, "invalid_input");
        Object project = input.get("projectId");
        if (project != null) {
            if (!(project instanceof String value)) throw new ApiFailure(400, "invalid_project");
            projects.get(caller, value);
        }
        Object supplied = input.getOrDefault("title", "新会话");
        if (!(supplied instanceof String title) || title.isBlank() || title.length() > 200)
            throw new ApiFailure(400, "invalid_title");
        var id = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO conversations(id,owner_id,title,project_id) VALUES(?,?,?,?)",
                id,
                caller.id(),
                title.strip(),
                project);
        return open(caller, id);
    }
}
