package io.iwork.model;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import io.iwork.identity.IdentityService;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@org.springframework.stereotype.Service
class ModelService {
    private final JdbcTemplate db;
    private final IdentityService identity;
    private final org.springframework.core.env.Environment environment;

    ModelService(
            JdbcTemplate db,
            IdentityService identity,
            org.springframework.core.env.Environment environment) {
        this.db = db;
        this.identity = identity;
        this.environment = environment;
    }

    record Model(
            String id,
            String name,
            String baseUrl,
            String modelName,
            String credentialRef,
            String inputPrice,
            String outputPrice,
            boolean enabled,
            Integer contextWindow) {}

    private List<Model> models(String condition, Object... args) {
        return db.query(
                "SELECT * FROM models WHERE " + condition + " ORDER BY name,id",
                (r, n) ->
                        new Model(
                                r.getString("id"),
                                r.getString("name"),
                                r.getString("base_url"),
                                r.getString("model_name"),
                                r.getString("credential_ref"),
                                r.getBigDecimal("input_price").toPlainString(),
                                r.getBigDecimal("output_price").toPlainString(),
                                r.getBoolean("enabled"),
                                (Integer) r.getObject("context_window")),
                args);
    }

    private void admin(Caller caller) {
        var live = identity.verify(caller, true);
        if (!live.role().equals("ADMIN") || live.mustChangePassword())
            throw new ApiFailure(403, "forbidden");
    }

    @Transactional
    List<Model> list(Caller caller) {
        admin(caller);
        return models("1=1");
    }

    @Transactional
    Model create(Caller caller, Map<String, Object> input) {
        admin(caller);
        var m = validated(UUID.randomUUID().toString(), input);
        db.queryForObject(
                "SELECT currency FROM model_settings WHERE id=1 FOR UPDATE", String.class);
        db.update(
                "INSERT INTO models(id,name,base_url,model_name,credential_ref,input_price,output_price,enabled,context_window) VALUES(?,?,?,?,?,?,?,?,?)",
                m.id(),
                m.name(),
                m.baseUrl(),
                m.modelName(),
                m.credentialRef(),
                m.inputPrice(),
                m.outputPrice(),
                m.enabled(),
                m.contextWindow());
        return models("id=?", m.id()).getFirst();
    }

    private Model validated(String id, Map<String, Object> input) {
        var keys = new java.util.HashSet<>(input.keySet());
        keys.remove("contextWindow");
        if (!keys.equals(
                        Set.of(
                                "name",
                                "baseUrl",
                                "modelName",
                                "credentialRef",
                                "inputPrice",
                                "outputPrice",
                                "enabled"))
                || !(input.get("enabled") instanceof Boolean enabled))
            throw new ApiFailure(400, "invalid_input");
        String name = text(input, "name", 100),
                url = text(input, "baseUrl", 1000),
                model = text(input, "modelName", 200),
                ref = text(input, "credentialRef", 100);
        try {
            var uri = URI.create(url);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new ApiFailure(400, "invalid_model_url");
        }
        if (!ref.matches("IWORK_MODEL_KEY_[A-Z0-9_]{1,80}"))
            throw new ApiFailure(400, "invalid_credential_reference");
        var credential = environment.getProperty(ref);
        if (credential == null || credential.isBlank())
            throw new ApiFailure(400, "credential_not_configured");
        Integer window = null;
        if (input.get("contextWindow") != null) {
            if (!(input.get("contextWindow") instanceof Integer number)
                    || number < 8192
                    || number > 131072) throw new ApiFailure(400, "invalid_context_window");
            window = number;
        }
        return new Model(
                id,
                name,
                url,
                model,
                ref,
                money(input.get("inputPrice")),
                money(input.get("outputPrice")),
                enabled,
                window);
    }

    @Transactional
    Model update(Caller caller, String id, Map<String, Object> input) {
        admin(caller);
        var m = validated(id, input);
        if (models("id=?", id).isEmpty()) throw new ApiFailure(404, "not_found");
        db.update(
                "UPDATE models SET name=?,base_url=?,model_name=?,credential_ref=?,input_price=?,output_price=?,enabled=?,context_window=? WHERE id=?",
                m.name(),
                m.baseUrl(),
                m.modelName(),
                m.credentialRef(),
                m.inputPrice(),
                m.outputPrice(),
                m.enabled(),
                m.contextWindow(),
                id);
        return models("id=?", id).getFirst();
    }

    record Settings(String currency, boolean locked) {}

    @Transactional
    Settings settings(Caller caller) {
        admin(caller);
        var currency =
                db.queryForObject(
                        "SELECT currency FROM model_settings WHERE id=1 FOR UPDATE", String.class);
        return new Settings(
                currency,
                db.queryForObject(
                                "SELECT (SELECT COUNT(*) FROM models)+(SELECT COUNT(*) FROM budgets)",
                                Integer.class)
                        > 0);
    }

    @Transactional
    Settings settings(Caller caller, Map<String, Object> input) {
        var previous = settings(caller);
        if (!input.keySet().equals(Set.of("currency"))) throw new ApiFailure(400, "invalid_input");
        var currency = text(input, "currency", 3);
        try {
            java.util.Currency.getInstance(currency);
        } catch (IllegalArgumentException error) {
            throw new ApiFailure(400, "invalid_currency");
        }
        if (previous.locked() && !previous.currency().equals(currency))
            throw new ApiFailure(409, "currency_locked");
        db.update("UPDATE model_settings SET currency=? WHERE id=1", currency);
        return settings(caller);
    }

    private static String text(Map<String, Object> input, String field, int max) {
        if (!(input.get(field) instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl))
            throw new ApiFailure(400, "invalid_input");
        return s.strip();
    }

    private static String money(Object value) {
        if (!(value instanceof String s) || !s.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,6})?"))
            throw new ApiFailure(400, "invalid_amount");
        return new BigDecimal(s).setScale(6).toPlainString();
    }

    record AvailableModel(String id, String name, String inputPrice, String outputPrice) {}

    List<AvailableModel> available(Caller caller) {
        return models(
                        "enabled=TRUE AND id IN (SELECT model_id FROM model_access WHERE user_id=?)",
                        caller.id())
                .stream()
                .map(m -> new AvailableModel(m.id(), m.name(), m.inputPrice(), m.outputPrice()))
                .toList();
    }

    private void target(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, id) == 0)
            throw new ApiFailure(404, "not_found");
    }

    @Transactional
    List<String> access(Caller caller, String id) {
        admin(caller);
        target(id);
        return db.queryForList(
                "SELECT model_id FROM model_access WHERE user_id=? ORDER BY model_id",
                String.class,
                id);
    }

    @Transactional
    List<String> access(Caller caller, String id, Map<String, Object> input) {
        admin(caller);
        target(id);
        if (!input.keySet().equals(Set.of("modelIds"))
                || !(input.get("modelIds") instanceof List<?> ids)
                || ids.size() > 100
                || ids.stream().anyMatch(v -> !(v instanceof String s) || s.length() != 36)
                || new java.util.HashSet<>(ids).size() != ids.size())
            throw new ApiFailure(400, "invalid_input");
        for (var modelId : ids)
            if (models("id=?", modelId).isEmpty()) throw new ApiFailure(400, "invalid_model");
        db.update("DELETE FROM model_access WHERE user_id=?", id);
        for (var modelId : ids)
            db.update("INSERT INTO model_access(user_id,model_id) VALUES(?,?)", id, modelId);
        return access(caller, id);
    }

    record Budget(String currency, String limit, String spent, String reserved, String available) {}

    private Budget budget(String id) {
        String currency =
                db.queryForObject("SELECT currency FROM model_settings WHERE id=1", String.class);
        var rows =
                db.query(
                        "SELECT * FROM budgets WHERE user_id=?",
                        (r, n) -> {
                            var limit = r.getBigDecimal("budget_limit");
                            var spent = r.getBigDecimal("spent");
                            var reserved = r.getBigDecimal("reserved");
                            return new Budget(
                                    currency,
                                    limit.toPlainString(),
                                    spent.toPlainString(),
                                    reserved.toPlainString(),
                                    limit.subtract(spent)
                                            .subtract(reserved)
                                            .max(BigDecimal.ZERO)
                                            .setScale(6)
                                            .toPlainString());
                        },
                        id);
        return rows.isEmpty()
                ? new Budget(currency, "0.000000", "0.000000", "0.000000", "0.000000")
                : rows.getFirst();
    }

    Budget ownBudget(Caller caller) {
        return budget(caller.id());
    }

    @Transactional
    Budget readBudget(Caller caller, String id) {
        admin(caller);
        target(id);
        return budget(id);
    }

    @Transactional
    Budget saveBudget(Caller caller, String id, Map<String, Object> input) {
        admin(caller);
        target(id);
        if (!input.keySet().equals(Set.of("limit"))) throw new ApiFailure(400, "invalid_input");
        var limit = money(input.get("limit"));
        db.queryForObject(
                "SELECT currency FROM model_settings WHERE id=1 FOR UPDATE", String.class);
        db.update(
                "INSERT INTO budgets(user_id,budget_limit) VALUES(?,?) ON DUPLICATE KEY UPDATE budget_limit=?",
                id,
                limit,
                limit);
        return budget(id);
    }
}
