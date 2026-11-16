package io.iwork.agent;

import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.model.*;
import io.agentscope.harness.agent.memory.compaction.*;
import io.agentscope.core.agent.RuntimeContext;
import io.iwork.conversation.TaskRunner;
import io.iwork.identity.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ConversationContext {
    private final JdbcTemplate db;
    private final io.iwork.model.GovernedModel models;
    public ConversationContext(JdbcTemplate db,io.iwork.model.GovernedModel models) { this.db=db; this.models=models; }
    public record Prepared(AgentState state,long through,String historyHash) {}
    private record History(long id,String role,String content) {}
    public Prepared load(TaskRunner.Input input) {
        var snapshots=db.queryForList("SELECT c.* FROM conversation_contexts c JOIN tasks t ON t.id=c.task_id WHERE c.owner_id=? AND c.conversation_id=? AND t.owner_id=c.owner_id AND t.conversation_id=c.conversation_id AND t.status='COMPLETED' ORDER BY c.created_at DESC LIMIT 1",input.caller().id(),input.conversationId());
        var snapshot=snapshots.isEmpty()?null:snapshots.getFirst();
        long mark=snapshot==null?0:((Number)snapshot.get("through_message_id")).longValue();
        var digest=digest(); var prefix=digest();
        var rebuilt=new ArrayList<Msg>(); var tail=new ArrayList<Msg>();
        long[] through={0}, rebuiltBytes={0}, tailBytes={0};
        boolean[] foundMark={mark==0};
        db.query(connection -> {
            var query=connection.prepareStatement("SELECT m.* FROM messages m JOIN tasks t ON t.id=m.task_id WHERE t.owner_id=? AND t.conversation_id=? AND t.status='COMPLETED' ORDER BY m.id");
            query.setString(1,input.caller().id()); query.setString(2,input.conversationId());
            query.setFetchSize(Integer.MIN_VALUE);
            return query;
        },(org.springframework.jdbc.core.RowCallbackHandler) r -> {
            var message=new History(r.getLong("id"),r.getString("role"),r.getString("content"));
            add(digest,message); through[0]=message.id();
            if(message.id()<=mark) add(prefix,message);
            if(message.id()==mark) foundMark[0]=true;
            var value=Msg.builder().name(message.role()).role(message.role().equals("user")?MsgRole.USER:MsgRole.ASSISTANT).textContent(message.content()).build();
            long bytes=message.content().getBytes(StandardCharsets.UTF_8).length+512L;
            rebuiltBytes[0]+=bytes;
            if(rebuiltBytes[0]<=2*1024*1024) rebuilt.add(value);
            if(message.id()>mark) {
                tailBytes[0]+=bytes;
                if(tailBytes[0]<=2*1024*1024) tail.add(value);
            }
        });
        var state=AgentState.builder().userId(input.caller().id()).sessionId(input.conversationId()).build();
        boolean restored=false;
        if(snapshot!=null && foundMark[0] && hex(prefix.digest()).equals(snapshot.get("history_hash"))) {
            String encoded=(String)snapshot.get("context_json");
            if(encoded.length()<=2*1024*1024 && hash(encoded).equals(snapshot.get("context_hash"))) {
                try {
                    var saved=AgentState.fromJsonString(encoded);
                    if(input.caller().id().equals(saved.getUserId()) && input.conversationId().equals(saved.getSessionId())
                        && saved.getContext().stream().allMatch(m -> (m.getRole()==MsgRole.USER || m.getRole()==MsgRole.ASSISTANT) && m.getContent().stream().allMatch(b -> b instanceof TextBlock))) {
                        state.contextMutable().addAll(saved.getContext());
                        state.setSummary(saved.getSummary()); restored=true;
                    }
                } catch(RuntimeException invalidSnapshot) { /* Rebuild from authoritative business messages. */ }
            }
        }
        db.update("UPDATE tasks SET context_state=?,context_at=CURRENT_TIMESTAMP(6) WHERE id=?",snapshot==null?"BUSINESS_HISTORY":restored?"RESTORED":"SNAPSHOT_REJECTED",input.taskId());
        if((restored?tailBytes[0]:rebuiltBytes[0])>2*1024*1024) throw new ApiFailure(413,"context_history_limit");
        state.contextMutable().addAll(restored?tail:rebuilt);
        compact(input,state);
        return new Prepared(state,through[0],hex(digest.digest()));
    }
    private void compact(TaskRunner.Input input,AgentState state) {
        var window=db.queryForObject("SELECT context_window FROM models WHERE id=?",Integer.class,input.modelId());
        if(window==null) throw new ApiFailure(409,"context_window_not_configured");
        int original=state.toJson().getBytes(StandardCharsets.UTF_8).length+input.message().getBytes(StandardCharsets.UTF_8).length+input.instructions().getBytes(StandardCharsets.UTF_8).length;
        int budget=window-4096;
        if(original<budget/2) { summaryState(input,"NOT_NEEDED"); return; }
        summaryState(input,"RUNNING");
        var completed=new java.util.concurrent.atomic.AtomicBoolean();
        var summary=new StringBuilder();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var governed=models.forTask(input,"SUMMARY");
        Model guarded=new Model() {
            public String getModelName() { return governed.getModelName(); }
            public reactor.core.publisher.Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                return governed.stream(messages,tools,options).doOnNext(response -> {
                    if(response.getContent()!=null) for(var block:response.getContent()) if(block instanceof TextBlock text) summary.append(text.getText());
                    if(summary.toString().getBytes(StandardCharsets.UTF_8).length>8192) throw new ApiFailure(502,"context_summary_limit");
                }).doOnComplete(() -> completed.set(true)).doOnError(failure::set);
            }
        };
        var config=CompactionConfig.builder().triggerMessages(1).triggerTokens(1).keepTokens(0).keepMessages(2)
            .flushBeforeCompact(false).offloadBeforeCompact(false).truncateArgs(null).prune(null).build();
        var context=RuntimeContext.builder().userId(input.caller().id()).sessionId(input.conversationId()).build();
        java.util.Optional<List<Msg>> compacted;
        try { compacted=new ConversationCompactor(guarded,null).compactIfNeeded(context,state.getContext(),config,input.taskId(),input.conversationId()).block(java.time.Duration.ofSeconds(125)); }
        catch(RuntimeException failureDuringSummary) { summaryState(input,Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED"); throw failureDuringSummary; }
        boolean accepted=false;
        // 2.0.4 converts summary errors and empty output to text; never persist those fallbacks.
        if(completed.get() && failure.get()==null && !summary.toString().isBlank() && compacted!=null && compacted.isPresent()) {
            var candidate=AgentState.builder().userId(input.caller().id()).sessionId(input.conversationId()).context(compacted.get()).summary(summary.toString()).build();
            if(candidate.toJson().getBytes(StandardCharsets.UTF_8).length+input.message().getBytes(StandardCharsets.UTF_8).length+input.instructions().getBytes(StandardCharsets.UTF_8).length<=budget) {
                state.contextMutable().clear(); state.contextMutable().addAll(compacted.get()); state.setSummary(summary.toString()); accepted=true;
            }
        }
        summaryState(input,accepted?"COMPACTED":original<=budget?"FALLBACK":"FAILED");
        if(!accepted && original>budget) throw new ApiFailure(413,"context_compaction_failed");
        if(state.toJson().getBytes(StandardCharsets.UTF_8).length+input.message().getBytes(StandardCharsets.UTF_8).length+input.instructions().getBytes(StandardCharsets.UTF_8).length>budget)
            throw new ApiFailure(413,"context_limit");
    }
    private void summaryState(TaskRunner.Input input,String state) {
        db.update("UPDATE tasks SET summary_state=?,summary_at=CURRENT_TIMESTAMP(6) WHERE id=?",state,input.taskId());
    }
    public void save(TaskRunner.Input input,Prepared prepared) {
        String encoded=prepared.state().toJson();
        db.update("INSERT INTO conversation_contexts(task_id,owner_id,conversation_id,through_message_id,history_hash,context_json,context_hash,summary) VALUES(?,?,?,?,?,?,?,?)",
            input.taskId(),input.caller().id(),input.conversationId(),prepared.through(),prepared.historyHash(),encoded,hash(encoded),prepared.state().getSummary());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void add(MessageDigest digest,History message) {
        digest.update((message.id()+":"+message.role()+":"+message.content().length()+":"+message.content()+"\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(String value) { return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
