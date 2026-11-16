package io.iwork.conversation;

import io.iwork.identity.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaskDiagnostics {
    private final JdbcTemplate db;
    private final IdentityService identity;
    public TaskDiagnostics(JdbcTemplate db,IdentityService identity) { this.db=db; this.identity=identity; }
    private static final String TASK = "SELECT id,owner_id AS ownerId,conversation_id AS conversationId,model_id AS modelId,status,execution_state AS executionState,error_code AS errorCode,created_at AS createdAt,updated_at AS updatedAt,context_state AS contextState,context_at AS contextAt,summary_state AS summaryState,summary_at AS summaryAt FROM tasks ";
    private void admin(Caller caller) {
        if(!identity.verify(caller,false).role().equals("ADMIN")) throw new ApiFailure(403,"forbidden");
    }
    public TaskService.Page<Map<String,Object>> recent(Caller caller,int page) {
        admin(caller);
        if(page<0 || page>100000) throw new ApiFailure(400,"invalid_pagination");
        var rows=db.queryForList(TASK+"ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",page*20);
        return new TaskService.Page<>(rows.stream().limit(20).toList(),rows.size()>20);
    }
    public Map<String,Object> get(Caller caller,String id,boolean administrative) {
        if(administrative) admin(caller);
        var rows=administrative?db.queryForList(TASK+"WHERE id=?",id):db.queryForList(TASK+"WHERE id=? AND owner_id=?",id,caller.id());
        if(rows.isEmpty()) throw new ApiFailure(404,"not_found");
        // Explicit columns exclude private content and untrusted provider response identifiers.
        var calls=db.queryForList("SELECT id,model_id AS modelId,purpose,status,source,error_code AS errorCode,input_estimate AS inputEstimate,provider_input AS inputTokens,provider_output AS outputTokens,output_bytes AS observedOutputBytes,CAST(charged AS CHAR) AS charged,CAST(reservation AS CHAR) AS reservation,currency,started_at AS startedAt,sent_at AS sentAt,usage_at AS usageAt,ended_at AS endedAt,CASE WHEN sent_at IS NULL THEN 'NOT_SENT' WHEN provider_complete THEN 'COMPLETED' ELSE 'UNKNOWN' END AS providerState FROM model_calls WHERE task_id=? ORDER BY started_at,id",id);
        var parses=db.queryForList("SELECT id,id AS sandboxJobId,material_id AS materialId,status,cleanup,error_code AS errorCode,created_at AS createdAt,updated_at AS updatedAt,cleanup_attempts AS cleanupAttempts,cleanup_attempted_at AS cleanupAttemptedAt,cleanup_confirmed_at AS cleanupConfirmedAt FROM material_parses WHERE task_id=? ORDER BY created_at,id",id);
        var versions=db.queryForList("SELECT id AS versionId,document_id AS documentId,version_number AS versionNumber,created_at AS createdAt FROM document_versions WHERE task_id=? ORDER BY created_at,id",id);
        return Map.of("task",rows.getFirst(),"calls",calls,"parses",parses,"versions",versions);
    }
}
