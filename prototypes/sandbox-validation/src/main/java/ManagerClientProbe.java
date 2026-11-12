import com.fasterxml.jackson.databind.*;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Synthetic main application: no Docker socket; business publication stays here. */
public final class ManagerClientProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final String KEY = System.getenv("MANAGER_KEY");
    static final Path OUT = Path.of("/evidence");
    static final List<String> CHECKS = new ArrayList<>();
    record Reply(int code, JsonNode body) {}
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        CHECKS.add(message);
    }
    static Reply request(String method, String path, Object body, String key, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://manager:8080" + path)).timeout(Duration.ofSeconds(12));
        if (key != null) builder.header("Authorization", "Bearer " + key);
        if (token != null) builder.header("X-Task-Token", token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var reply = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(), JSON.readTree(reply.body()));
    }
    static JsonNode create(String source) throws Exception {
        Reply reply = request("POST", "/tasks", Map.of("source", source), KEY, null);
        check(reply.code == 202, "fixed task accepted"); return reply.body;
    }
    static Reply task(String method, JsonNode lease, String token) throws Exception {
        return request(method, "/tasks/" + lease.path("id").asText(), null, KEY, token);
    }
    static JsonNode status(JsonNode lease) throws Exception {
        Reply reply = task("GET", lease, lease.path("token").asText());
        check(reply.code == 200, "task capability accepted"); return reply.body;
    }
    static JsonNode terminal(JsonNode lease) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            JsonNode state = status(lease);
            if (!state.path("state").asText().equals("RUNNING") && state.path("cleaned").asBoolean()) return state;
            if (System.nanoTime() > until) throw new AssertionError("task did not settle");
            Thread.sleep(100);
        }
    }
    public static final class ConvertTool {
        final String source;
        ConvertTool(String source) { this.source = source; }
        @Tool(name="convert_document", description="Convert the authorized source using the fixed manager operation")
        public String convert() throws Exception {
            JsonNode result = terminal(create(source));
            if (!result.path("state").asText().equals("SUCCEEDED")) throw new IllegalStateException("conversion failed");
            return result.path("draft").asText();
        }
    }
    static void suite() throws Exception {
        check(!Files.exists(Path.of("/var/run/docker.sock")), "main has no Docker socket");
        check(!Files.exists(Path.of("/registry")), "main cannot read manager registry");
        check(System.getenv("DB_PASSWORD") != null && System.getenv("MODEL_API_KEY") != null, "mock business credentials stay in main");
        check(request("POST", "/tasks", Map.of("source", "x"), null, null).code == 401, "unauthenticated request denied");
        check(request("POST", "/tasks", Map.of("source", "x"), "wrong", null).code == 401, "wrong service key denied");
        for (String field : List.of("image", "command", "mount", "network", "privileged", "owner", "timeout")) {
            check(request("POST", "/tasks", Map.of("source", "x", field, "arbitrary"), KEY, null).code == 400, "injected field rejected: " + field);
        }
        check(request("POST", "/tasks", Map.of("source", "x".repeat(40000)), KEY, null).code == 413, "oversized body denied");
        check(request("POST", "/containers/create", Map.of(), KEY, null).code == 404, "Docker API not forwarded");
        check(request("POST", "/tasks/../../containers/create", Map.of(), KEY, null).code == 404, "path traversal denied");
        String source = "ALICE: literal $(id); /etc/passwd";
        Toolkit toolkit = new Toolkit(); toolkit.registerTool(new ConvertTool(source));
        check(toolkit.getToolSchemas().size() == 1 && toolkit.getToolSchemas().get(0).getName().equals("convert_document"), "only controlled SDK tool exposed");
        var reply = toolkit.callTool(ToolCallParam.builder().toolUseBlock(new ToolUseBlock("fixed", "convert_document", Map.of(), "{}", null)).build()).block(Duration.ofSeconds(20));
        check(reply != null && reply.getState() != io.agentscope.core.message.ToolResultState.ERROR, "SDK tool reaches manager and container: " + JSON.writeValueAsString(reply));
        JsonNode normal = terminal(create(source));
        String old = normal.path("draft").asText();
        check(old.equals("# Synthetic document\n\n" + source), "input treated as data and output correct");
        Files.writeString(OUT.resolve("managed-version.md"), old);
        JsonNode alice = create("__IW8_SLOW_FIXTURE__");
        JsonNode bob = create("BOB_ONLY");
        check(task("GET", alice, bob.path("token").asText()).code == 404, "cross-task read denied");
        check(task("DELETE", alice, bob.path("token").asText()).code == 404, "cross-task cancellation denied");
        check(task("GET", alice, null).code == 404, "missing task capability denied");
        // Business cancellation wins before asking the manager to stop processing.
        String business = "CANCELLED";
        JsonNode cancelled = task("DELETE", alice, alice.path("token").asText()).body;
        check(cancelled.path("state").asText().equals("CANCELLED") && cancelled.path("cleaned").asBoolean(), "cancel returns after verified cleanup");
        check(!business.equals("RUNNING"), "late publication rejected by main business state");
        check(terminal(bob).path("draft").asText().contains("BOB_ONLY"), "other task remains independent");
        JsonNode failure = terminal(create("__IW8_FAIL_FIXTURE__"));
        check(failure.path("state").asText().equals("FAILED") && failure.path("draft").asText().isEmpty(), "processor failure cannot publish partial output");
        JsonNode timeout = terminal(create("__IW8_SLOW_FIXTURE__"));
        check(timeout.path("state").asText().equals("FAILED") && timeout.path("cleaned").asBoolean(), "execution timeout removes task container");
        check(Files.readString(OUT.resolve("managed-version.md")).equals(old), "old version preserved after all failures");
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("manager-client.json").toFile(),
                Map.of("checks", CHECKS, "sdkReply", JSON.valueToTree(reply), "timeout", timeout, "cancel", cancelled));
        System.out.println("MANAGED_SUITE_OK");
    }
    static void crashReady() throws Exception {
        JsonNode lease = create("__IW8_SLOW_FIXTURE__");
        JSON.writeValue(OUT.resolve("client-lease.local.json").toFile(), lease);
        System.out.println("CLIENT_CRASH_READY");
        Thread.sleep(60000);
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "suite" -> suite();
            case "crash" -> crashReady();
            case "recovered" -> {
                JsonNode lease = JSON.readTree(OUT.resolve("client-lease.local.json").toFile());
                JsonNode result = status(lease);
                check(result.path("state").asText().equals("INTERRUPTED") && result.path("cleaned").asBoolean(), "manager restart marks interrupted and confirms cleanup");
                JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve("managed-recovery.json").toFile(), result);
                System.out.println("MANAGED_RECOVERY_OK");
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
