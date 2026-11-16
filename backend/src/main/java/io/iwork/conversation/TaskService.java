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
public class TaskService implements ApplicationRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;
    private final ModelAuthorization models;
    private final ObjectProvider<TaskRunner> runners;
    private final Semaphore capacity;
    private final ConcurrentMap<String,Thread> workers=new ConcurrentHashMap<>();
    private volatile boolean stopping;
    public TaskService(JdbcTemplate db,TransactionTemplate tx,IdentityService identity,ModelAuthorization models,
            ObjectProvider<TaskRunner> runners,@Value("${iwork.task-capacity:2}") int capacity) {
        if(capacity<1 || capacity>16) throw new IllegalArgumentException("Task capacity must be 1 to 16");
        this.db=db; this.tx=tx; this.identity=identity; this.models=models; this.runners=runners; this.capacity=new Semaphore(capacity);
    }
    public record Task(String id,String conversationId,String modelId,String requestId,String status,String errorCode,
                       String executionState,Instant createdAt,Instant updatedAt) {}
    public record Message(long id,String taskId,String role,String content,Instant createdAt) {}
    public record Page<T>(List<T> items,boolean hasMore) {}
    private List<Task> query(String condition,Object... args) {
        return db.query("SELECT * FROM tasks WHERE "+condition,(r,n)->new Task(r.getString("id"),r.getString("conversation_id"),r.getString("model_id"),
            r.getString("request_id"),r.getString("status"),r.getString("error_code"),r.getString("execution_state"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()),args);
    }
    @Override public void run(ApplicationArguments args) {
        db.update("UPDATE tasks SET status='INTERRUPTED',execution_state='UNKNOWN',error_code='process_restarted',updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING'");
        db.update("UPDATE tasks SET execution_state='UNKNOWN' WHERE execution_state IN ('RUNNING','STOP_REQUESTED')");
    }
    public Task get(Caller caller,String id) {
        var rows=query("id=? AND owner_id=?",id,caller.id());
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
        return rows.getFirst();
    }
    private void conversation(Caller caller,String id,boolean lock) {
        var rows=db.queryForList("SELECT id FROM conversations WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),String.class,id,caller.id());
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
    }
    public Page<Task> list(Caller caller,String conversationId,int page) {
        if(page<0 || page>100000) throw new ApiFailure(400,"invalid_pagination");
        conversation(caller,conversationId,false);
        var rows=query("owner_id=? AND conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",caller.id(),conversationId,page*20);
        return new Page<>(rows.stream().limit(20).toList(),rows.size()>20);
    }
    public Page<Message> messages(Caller caller,String conversationId,long after) {
        if(after<0) throw new ApiFailure(400,"invalid_pagination");
        conversation(caller,conversationId,false);
        var rows=db.query("SELECT * FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT 101",(r,n)->
            new Message(r.getLong("id"),r.getString("task_id"),r.getString("role"),r.getString("content"),r.getTimestamp("created_at").toInstant()),conversationId,after);
        return new Page<>(rows.stream().limit(100).toList(),rows.size()>100);
    }
    public Task submit(Caller caller,String conversationId,String requestId,String modelId,String message) {
        if(requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,100}") || modelId==null || !modelId.matches("[a-fA-F0-9-]{36}")
            || message==null || message.isBlank() || message.length()>20000) throw new ApiFailure(400,"invalid_task");
        final String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((conversationId+"\n"+modelId+"\n"+message).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        boolean[] acquired={false};
        Task accepted;
        try {
            accepted=tx.execute(status -> {
                var live=identity.verify(caller,true);
                if(live.mustChangePassword()) throw new ApiFailure(403,"password_change_required");
                conversation(caller,conversationId,true);
                var existing=query("owner_id=? AND request_id=?",caller.id(),requestId);
                if(!existing.isEmpty()) {
                    var old=existing.getFirst();
                    var oldHash=db.queryForObject("SELECT request_hash FROM tasks WHERE id=?",String.class,old.id());
                    if(!hash.equals(oldHash)) throw new ApiFailure(409,"idempotency_conflict");
                    return old;
                }
                if(runners.getIfAvailable()==null || stopping) throw new ApiFailure(503,"execution_unavailable");
                models.require(caller.id(),modelId);
                if(!query("conversation_id=? AND status='RUNNING'",conversationId).isEmpty()) throw new ApiFailure(409,"conversation_busy");
                if(!capacity.tryAcquire()) throw new ApiFailure(429,"task_capacity_exceeded");
                acquired[0]=true;
                String id=UUID.randomUUID().toString();
                db.update("INSERT INTO tasks(id,owner_id,conversation_id,model_id,request_id,request_hash,status,execution_state) VALUES(?,?,?,?,?,?,'RUNNING','RUNNING')",id,caller.id(),conversationId,modelId,requestId,hash);
                db.update("INSERT INTO messages(task_id,conversation_id,role,content) VALUES(?,?,'user',?)",id,conversationId,message);
                db.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",conversationId);
                return get(caller,id);
            });
        } catch(RuntimeException failure) { if(acquired[0]) capacity.release(); throw failure; }
        if(acquired[0]) {
            var input=new TaskRunner.Input(accepted.id(),conversationId,caller,modelId,message);
            Thread worker=Thread.ofVirtual().unstarted(() -> execute(input));
            workers.put(accepted.id(),worker);
            worker.start();
        }
        return accepted;
    }
    private void execute(TaskRunner.Input input) {
        try {
            if(!get(input.caller(),input.taskId()).status().equals("RUNNING") || stopping) return;
            String reply=runners.getObject().execute(input);
            if(reply==null || reply.isBlank() || reply.length()>200000) throw new IllegalStateException("Invalid execution result");
            tx.executeWithoutResult(status -> {
                var current=query("id=? FOR UPDATE",input.taskId()).getFirst();
                if(!current.status().equals("RUNNING")) return;
                db.update("INSERT INTO messages(task_id,conversation_id,role,content) VALUES(?,?,'assistant',?)",input.taskId(),input.conversationId(),reply);
                db.update("UPDATE tasks SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.taskId());
                db.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.conversationId());
            });
        } catch(Exception failure) {
            Thread.interrupted();
            persist(() -> db.update("UPDATE tasks SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RUNNING'",
                stopping?"INTERRUPTED":"FAILED",stopping?"process_restarted":"execution_failed",input.taskId()));
        } finally {
            Thread.interrupted();
            try { persist(() -> db.update("UPDATE tasks SET execution_state='STOPPED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.taskId())); }
            finally { workers.remove(input.taskId()); capacity.release(); }
        }
    }
    private void persist(Runnable write) {
        while(true) {
            try { write.run(); return; }
            catch(org.springframework.dao.DataAccessException unavailable) {
                if(stopping) return;
                try { Thread.sleep(1000); } catch(InterruptedException interrupted) { if(stopping) return; }
            }
        }
    }
    public Task cancel(Caller caller,String id) {
        var cancelled=tx.execute(status -> {
            identity.verify(caller,true);
            var rows=query("id=? AND owner_id=? FOR UPDATE",id,caller.id());
            if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
            db.update("UPDATE tasks SET status='CANCELLED',execution_state='STOP_REQUESTED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_id=? AND status='RUNNING'",id,caller.id());
            return get(caller,id);
        });
        if(cancelled.status().equals("CANCELLED")) {
            var worker=workers.get(id); if(worker!=null) worker.interrupt();
        }
        return cancelled;
    }
    @PreDestroy void close() { stopping=true; workers.values().forEach(Thread::interrupt); }
}
