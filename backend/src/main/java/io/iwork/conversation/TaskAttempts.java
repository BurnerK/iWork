package io.iwork.conversation;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
public class TaskAttempts {
    private final JdbcTemplate db;

    public TaskAttempts(JdbcTemplate db) {
        this.db = db;
    }

    public List<String> through(String taskId) {
        // 素材和草稿按执行次数归属，查询旧任务时只能看到当时已有的执行记录。
        return db.queryForList(
                """
                SELECT t.id FROM tasks requested JOIN tasks t
                  ON (t.id=COALESCE(requested.reply_task_id,requested.id)
                      OR t.reply_task_id=COALESCE(requested.reply_task_id,requested.id))
                WHERE requested.id=? AND t.queue_order<=requested.queue_order
                """,
                String.class,
                taskId);
    }

    public record CompletedMessage(long id, String role, String content) {}

    public void forEachCompletedMessage(
            String ownerId, String conversationId, Consumer<CompletedMessage> consumer) {
        db.query(
                connection -> {
                    var query =
                            connection.prepareStatement(
                                    """
                            SELECT m.id,m.role,m.content FROM messages m JOIN tasks t ON t.id=m.task_id
                            WHERE t.owner_id=? AND t.conversation_id=? AND (
                                t.status='COMPLETED' OR (m.role='user' AND EXISTS (
                                    SELECT 1 FROM tasks n WHERE n.reply_task_id=t.id AND n.status='COMPLETED')))
                            ORDER BY m.id
                            """);
                    query.setString(1, ownerId);
                    query.setString(2, conversationId);
                    // MySQL Connector/J 用这个值开启逐行读取，避免把完整会话一次载入内存。
                    query.setFetchSize(Integer.MIN_VALUE);
                    return query;
                },
                (RowCallbackHandler)
                        row ->
                                consumer.accept(
                                        new CompletedMessage(
                                                row.getLong("id"),
                                                row.getString("role"),
                                                row.getString("content"))));
    }
}
