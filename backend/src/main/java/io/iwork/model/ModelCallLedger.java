package io.iwork.model;

import io.iwork.conversation.TaskRunner;
import io.iwork.identity.*;
import java.math.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@org.springframework.core.annotation.Order(1)
public class ModelCallLedger implements ApplicationRunner {
    public static final int MAX_OUTPUT = 2048;
    private volatile boolean stopping;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IdentityService identity;
    private final ModelAuthorization authorization;

    public ModelCallLedger(
            JdbcTemplate db,
            TransactionTemplate tx,
            IdentityService identity,
            ModelAuthorization authorization) {
        this.db = db;
        this.tx = tx;
        this.identity = identity;
        this.authorization = authorization;
    }

    public record Attempt(String id, String baseUrl, String modelName, String credentialRef) {}

    private static BigDecimal cost(
            long input, long output, BigDecimal inputPrice, BigDecimal outputPrice) {
        return inputPrice
                .multiply(BigDecimal.valueOf(input))
                .add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1000000), 6, RoundingMode.CEILING);
    }

    public Attempt begin(TaskRunner.Input input, String purpose, int inputBytes) {
        if (!Set.of("MAIN", "SUMMARY", "OVERFLOW").contains(purpose)
                || inputBytes < 1
                || inputBytes > 130560) throw new ApiFailure(400, "model_input_limit");
        return tx.execute(
                status -> {
                    identity.verify(input.caller(), true);
                    var task =
                            db.queryForList(
                                    "SELECT status FROM tasks WHERE id=? AND owner_id=? AND conversation_id=? AND model_id=? FOR UPDATE",
                                    String.class,
                                    input.taskId(),
                                    input.caller().id(),
                                    input.conversationId(),
                                    input.modelId());
                    if (task.isEmpty() || !task.getFirst().equals("RUNNING"))
                        throw new ApiFailure(409, "task_not_running");
                    authorization.require(input.caller().id(), input.modelId());
                    if (db.queryForObject(
                                    "SELECT COUNT(*) FROM model_calls WHERE task_id=?",
                                    Integer.class,
                                    input.taskId())
                            >= 4) throw new ApiFailure(429, "model_attempt_limit");
                    var model = db.queryForMap("SELECT * FROM models WHERE id=?", input.modelId());
                    var window = (Number) model.get("context_window");
                    if (window == null) throw new ApiFailure(409, "context_window_not_configured");
                    if (inputBytes + 512 + MAX_OUTPUT > window.intValue())
                        throw new ApiFailure(413, "model_context_limit");
                    var reservation =
                            cost(
                                    inputBytes + 512,
                                    MAX_OUTPUT,
                                    (BigDecimal) model.get("input_price"),
                                    (BigDecimal) model.get("output_price"));
                    var budgets =
                            db.queryForList(
                                    "SELECT * FROM budgets WHERE user_id=? FOR UPDATE",
                                    input.caller().id());
                    if (budgets.isEmpty()) throw new ApiFailure(402, "budget_exhausted");
                    var budget = budgets.getFirst();
                    var available =
                            ((BigDecimal) budget.get("budget_limit"))
                                    .subtract((BigDecimal) budget.get("spent"))
                                    .subtract((BigDecimal) budget.get("reserved"));
                    if (available.compareTo(reservation) < 0)
                        throw new ApiFailure(402, "budget_exhausted");
                    String id = UUID.randomUUID().toString();
                    db.update(
                            "INSERT INTO model_calls(id,task_id,owner_id,model_id,purpose,input_estimate,input_price,output_price,currency,reservation) VALUES(?,?,?,?,?,?,?,?,(SELECT currency FROM model_settings WHERE id=1),?)",
                            id,
                            input.taskId(),
                            input.caller().id(),
                            input.modelId(),
                            purpose,
                            inputBytes + 512,
                            model.get("input_price"),
                            model.get("output_price"),
                            reservation);
                    db.update(
                            "UPDATE budgets SET reserved=reserved+? WHERE user_id=?",
                            reservation,
                            input.caller().id());
                    return new Attempt(
                            id,
                            (String) model.get("base_url"),
                            (String) model.get("model_name"),
                            (String) model.get("credential_ref"));
                });
    }

    public void sent(String id) {
        db.update(
                "UPDATE model_calls SET sent_at=COALESCE(sent_at,CURRENT_TIMESTAMP(6)) WHERE id=?",
                id);
    }

    private Map<String, Object> locked(String id) {
        var owner =
                db.queryForObject("SELECT owner_id FROM model_calls WHERE id=?", String.class, id);
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, owner);
        return db.queryForMap("SELECT * FROM model_calls WHERE id=? FOR UPDATE", id);
    }

    private static Long tokens(Long value) {
        return value != null && value >= 0 && value <= 1000000000000L ? value : null;
    }

    private static Long maximum(Object before, Long next) {
        if (next == null) return (Long) before;
        if (before == null) return next;
        return Math.max((Long) before, next);
    }

    public void observe(
            String id, long outputBytes, Long inputTokens, Long outputTokens, String providerId) {
        if (outputBytes < 0 || outputBytes > 1048576)
            throw new ApiFailure(502, "model_output_limit");
        tx.executeWithoutResult(
                status -> {
                    var call = locked(id);
                    Long in = maximum(call.get("provider_input"), tokens(inputTokens)),
                            out = maximum(call.get("provider_output"), tokens(outputTokens));
                    long observed =
                            Math.max(((Number) call.get("output_bytes")).longValue(), outputBytes);
                    if (observed > 1048576) throw new ApiFailure(502, "model_output_limit");
                    String responseId =
                            providerId != null && providerId.length() <= 200
                                    ? providerId
                                    : (String) call.get("provider_response_id");
                    db.update(
                            "UPDATE model_calls SET output_bytes=?,provider_input=?,provider_output=?,provider_response_id=?,usage_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            observed,
                            in,
                            out,
                            responseId,
                            id);
                    if (call.get("status").equals("CLOSED")) settle(id);
                });
    }

    public void complete(String id) {
        tx.executeWithoutResult(
                status -> {
                    var call = locked(id);
                    db.update("UPDATE model_calls SET provider_complete=TRUE WHERE id=?", id);
                    if (call.get("status").equals("CLOSED")) settle(id);
                });
    }

    public void failure(String id, Throwable failure) {
        String code = "model_failed";
        for (int i = 0; i < 10 && failure != null; i++, failure = failure.getCause()) {
            if (failure instanceof ApiFailure
                    && Set.of(
                                    "model_timeout",
                                    "model_output_limit",
                                    "model_input_limit",
                                    "model_context_limit",
                                    "model_not_authorized",
                                    "task_not_running",
                                    "credential_not_configured",
                                    "budget_exhausted")
                            .contains(failure.getMessage())) {
                code = failure.getMessage();
                break;
            }
        }
        db.update("UPDATE model_calls SET error_code=COALESCE(error_code,?) WHERE id=?", code, id);
    }

    public void cancelled(String id) {
        db.update(
                "UPDATE model_calls SET error_code=COALESCE(error_code,'local_cancelled') WHERE id=?",
                id);
    }

    public void finish(String id) {
        tx.executeWithoutResult(
                status -> {
                    locked(id);
                    settle(id);
                });
    }

    private void settle(String id) {
        var call = db.queryForMap("SELECT * FROM model_calls WHERE id=? FOR UPDATE", id);
        boolean wasOpen = call.get("status").equals("OPEN"), sent = call.get("sent_at") != null;
        Long in = (Long) call.get("provider_input"), out = (Long) call.get("provider_output");
        long observed = ((Number) call.get("output_bytes")).longValue();
        boolean complete = (Boolean) call.get("provider_complete");
        String source =
                !sent
                        ? "NOT_SENT"
                        : complete && in != null && out != null
                                ? "PROVIDER"
                                : in != null || out != null
                                        ? "PARTIAL_ESTIMATE"
                                        : observed == 0 ? "LIMIT_ESTIMATE" : "OBSERVED_ESTIMATE";
        var charge =
                sent
                        ? cost(
                                in != null ? in : ((Number) call.get("input_estimate")).longValue(),
                                complete && out != null
                                        ? out
                                        : Math.max(
                                                out == null ? 0 : out,
                                                observed == 0 ? MAX_OUTPUT : observed),
                                (BigDecimal) call.get("input_price"),
                                (BigDecimal) call.get("output_price"))
                        : BigDecimal.ZERO.setScale(6);
        var previous = (BigDecimal) call.get("charged");
        if (wasOpen || charge.compareTo(previous) != 0 || !source.equals(call.get("source"))) {
            db.update(
                    "UPDATE budgets SET spent=spent+?,reserved=reserved-? WHERE user_id=?",
                    charge.subtract(previous),
                    wasOpen ? call.get("reservation") : BigDecimal.ZERO,
                    call.get("owner_id"));
            db.update(
                    "INSERT INTO model_call_adjustments(call_id,previous_amount,new_amount,source,provider_input,provider_output) VALUES(?,?,?,?,?,?)",
                    id,
                    previous,
                    charge,
                    source,
                    in,
                    out);
        }
        db.update(
                "UPDATE model_calls SET charged=?,source=?,status='CLOSED',ended_at=COALESCE(ended_at,CURRENT_TIMESTAMP(6)) WHERE id=?",
                charge,
                source,
                id);
    }

    public boolean retain(Runnable write) {
        Thread.interrupted();
        while (true) {
            try {
                write.run();
                return true;
            } catch (org.springframework.dao.DataAccessException
                    | org.springframework.transaction.TransactionException unavailable) {
                if (stopping) return false;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    if (stopping) return false;
                }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void close() {
        stopping = true;
    }

    public void finishTask(String taskId) {
        db.queryForList(
                        "SELECT id FROM model_calls WHERE task_id=? AND status='OPEN'",
                        String.class,
                        taskId)
                .forEach(this::finish);
    }

    @Override
    public void run(ApplicationArguments args) {
        db.queryForList("SELECT id FROM model_calls WHERE status='OPEN'", String.class)
                .forEach(this::finish);
    }

    public List<Map<String, Object>> list(Caller caller, String taskId) {
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM tasks WHERE id=? AND owner_id=?",
                        Integer.class,
                        taskId,
                        caller.id())
                == 0) throw new ApiFailure(404, "not_found");
        return db.query(
                "SELECT * FROM model_calls WHERE task_id=? ORDER BY started_at,id",
                (r, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", r.getString("id"));
                    item.put("purpose", r.getString("purpose"));
                    item.put("status", r.getString("status"));
                    item.put("source", r.getString("source"));
                    item.put("inputTokens", r.getObject("provider_input"));
                    item.put("outputTokens", r.getObject("provider_output"));
                    item.put("inputEstimate", r.getLong("input_estimate"));
                    item.put("observedOutputBytes", r.getLong("output_bytes"));
                    item.put("charged", r.getBigDecimal("charged").toPlainString());
                    item.put("reservation", r.getBigDecimal("reservation").toPlainString());
                    item.put("currency", r.getString("currency"));
                    item.put("providerResponseId", r.getString("provider_response_id"));
                    item.put("startedAt", r.getTimestamp("started_at").toInstant());
                    item.put(
                            "endedAt",
                            r.getTimestamp("ended_at") == null
                                    ? null
                                    : r.getTimestamp("ended_at").toInstant());
                    item.put(
                            "usageAt",
                            r.getTimestamp("usage_at") == null
                                    ? null
                                    : r.getTimestamp("usage_at").toInstant());
                    item.put(
                            "adjustments",
                            db.queryForList(
                                    "SELECT previous_amount,new_amount,source,provider_input,provider_output,created_at FROM model_call_adjustments WHERE call_id=? ORDER BY id",
                                    r.getString("id")));
                    return item;
                },
                taskId);
    }
}
