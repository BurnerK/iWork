import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class RuntimeProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, String> FIXTURES = Map.of(
            "alice-token", "alice", "bob-token", "bob");
    final Path records;
    final Path work;
    final Map<String, ObjectNode> tasks = new LinkedHashMap<>();
    final Map<String, Sinks.One<Integer>> gates = new HashMap<>();
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    RuntimeProbe(Path root) throws IOException {
        records = Files.createDirectories(root.resolve("records"));
        work = Files.createDirectories(root.resolve("work"));
        try (var files = Files.list(records)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                ObjectNode task = (ObjectNode) JSON.readTree(file.toFile());
                if (running(task)) save(task.put("state", "INTERRUPTED").put("phase", "restart"));
                else tasks.put(task.path("id").asText(), task);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        RuntimeProbe probe = new RuntimeProbe(Path.of(args[0]));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", probe::http);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println(server.getAddress().getPort());
    }

    static boolean running(ObjectNode task) {
        return task.path("state").asText().equals("RUNNING");
    }

    // One record commits task state and its immutable document version together.
    synchronized void save(ObjectNode task) throws IOException {
        save(task, false);
    }

    private void save(ObjectNode task, boolean failBeforeMove) throws IOException {
        String id = task.path("id").asText();
        Path temp = Files.createTempFile(records, id, ".pending");
        try {
            JSON.writeValue(temp.toFile(), task);
            if (failBeforeMove) throw new IOException("injected save failure before atomic move");
            Files.move(temp, records.resolve(id + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tasks.put(id, task);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    record Reply(int status, Object body) {}

    synchronized Reply route(String method, String[] path, String user, JsonNode body)
            throws IOException {
        if (user == null) return new Reply(401, Map.of("error", "fixture token required"));
        if (path.length == 2 && path[1].equals("tasks") && method.equals("POST")) {
            String key = body.path("key").asText();
            String session = body.path("session").asText();
            String source = body.path("source").asText();
            String base = body.path("base").asText();
            String mode = body.path("mode").asText("normal");
            if (key.isBlank() || session.isBlank() || source.isBlank()
                    || !Set.of("normal", "cancel-model", "model-fail", "save-fail").contains(mode))
                return new Reply(400, Map.of("error", "invalid request"));
            for (ObjectNode t : tasks.values()) {
                if (t.path("owner").asText().equals(user) && t.path("key").asText().equals(key)) {
                    boolean same = t.path("request").equals(body);
                    return same ? new Reply(200, t.deepCopy())
                            : new Reply(409, Map.of("error", "key already used"));
                }
            }
            ObjectNode prior = base.isEmpty() ? null : owned(user, base);
            if (!base.isEmpty() && (prior == null || !prior.has("document")))
                return new Reply(404, Map.of("error", "version not found"));
            if (tasks.values().stream().anyMatch(t -> running(t)
                    && t.path("owner").asText().equals(user) && t.path("session").asText().equals(session)))
                return new Reply(409, Map.of("error", "session busy"));
            String id = UUID.randomUUID().toString();
            ObjectNode task = JSON.createObjectNode().put("id", id).put("key", key)
                    .put("owner", user).put("session", session).put("state", "RUNNING")
                    .put("phase", "accepted").put("model", "deterministic-stub");
            task.set("request", body);
            task.putObject("calls");
            save(task);
            var gate = Sinks.<Integer>one();
            gates.put(id, gate);
            String input = prior == null ? source : prior.path("document").asText() + "\n" + source;
            workers.submit(() -> run(id, input, mode, gate));
            return new Reply(202, task.deepCopy());
        }
        if (path.length >= 3 && path[1].equals("tasks")) {
            ObjectNode task = owned(user, path[2]);
            if (task != null) {
                if (path.length == 3 && method.equals("GET")) return new Reply(200, task.deepCopy());
                if (path.length == 4 && method.equals("POST") && path[3].equals("release")) {
                    var gate = gates.get(path[2]);
                    if (gate != null) gate.tryEmitValue(1);
                    return new Reply(200, task.deepCopy());
                }
                if (path.length == 4 && method.equals("POST") && path[3].equals("cancel")) {
                    if (running(task)) {
                        task = task.deepCopy().put("state", "CANCELLED").put("execution", "stop-unconfirmed");
                        save(task);
                        if (task.path("request").path("mode").asText().equals("cancel-model")) {
                            var stop = stops.get(path[2]);
                            if (stop != null) stop.tryEmitValue(1);
                        }
                    }
                    return new Reply(200, task.deepCopy());
                }
            }
        }
        return new Reply(404, Map.of("error", "not found"));
    }

    ObjectNode owned(String user, String id) {
        var task = tasks.get(id);
        return task != null && task.path("owner").asText().equals(user) ? task : null;
    }

    void http(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/")) {
                try (var page = RuntimeProbe.class.getResourceAsStream("/probe.html")) {
                    byte[] bytes = Objects.requireNonNull(page).readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                return;
            }
            Reply reply;
            try {
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                String token = exchange.getRequestHeaders().getFirst("X-Probe-Token");
                reply = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath().split("/"),
                        token == null ? null : FIXTURES.get(token), body == null ? JSON.createObjectNode() : body);
            } catch (IOException error) {
                reply = new Reply(500, Map.of("error", error.toString()));
            }
            byte[] bytes = JSON.writeValueAsBytes(reply.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    final Map<String, Sinks.One<Integer>> stops = new HashMap<>();

    synchronized void update(String id, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode task = tasks.get(id).deepCopy();
        change.accept(task);
        try {
            save(task);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    public static final class BuiltIns {
        final Path workspace;
        BuiltIns(Path workspace) { this.workspace = workspace; }

        @Tool(name = "read_source", description = "Read the input granted to this task")
        public String readSource() throws IOException {
            return Files.readString(workspace.resolve("source.txt"));
        }

        @Tool(name = "draft_document", description = "Write a text document into the task workspace")
        public String draftDocument() throws IOException {
            Files.writeString(workspace.resolve("draft.md"), "# 资料文档\n\n" + readSource());
            return "draft staged";
        }
    }

    void run(String id, String input, String mode, Sinks.One<Integer> gate) {
        Path workspace = work.resolve(id);
        var stop = Sinks.<Integer>one();
        synchronized (this) {
            stops.put(id, stop);
            if (mode.equals("cancel-model") && !running(tasks.get(id))) stop.tryEmitValue(1);
        }
        try {
            Files.createDirectory(workspace);
            Files.writeString(workspace.resolve("source.txt"), input, StandardCharsets.UTF_8);
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new BuiltIns(workspace));
            Model model = new Model() {
                int step;
                public String getModelName() { return "deterministic-stub"; }

                public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> schemas,
                        GenerateOptions options) {
                    int call = ++step;
                    if (call == 2 && mode.equals("model-fail"))
                        return Flux.error(new IllegalStateException("injected model failure"));
                    if (call == 2 && mode.equals("cancel-model"))
                        return Flux.<ChatResponse>never().doOnCancel(() ->
                                update(id, t -> t.put("modelSubscriptionCancelled", true)));
                    ContentBlock block = switch (call) {
                        case 1 -> new ToolUseBlock("read", "read_source", Map.of());
                        case 2 -> new ToolUseBlock("draft", "draft_document", Map.of());
                        default -> TextBlock.builder().text("文档草稿已生成").build();
                    };
                    return Flux.just(ChatResponse.builder().id("stub-" + call).content(List.of(block))
                            .usage(new ChatUsage(10, call, 0)).build());
                }
            };
            try (var agent = ReActAgent.builder().name("runtime-probe").model(model)
                    .toolkit(toolkit).maxIters(4).maxRetries(1).build()) {
                var events = agent.streamEvents("读取获准资料并生成文字文档")
                        .doOnNext(event -> {
                            if (event instanceof ModelCallStartEvent start)
                                update(id, t -> ((ObjectNode) t.path("calls")).putNull(start.getReplyId()));
                            if (event instanceof ModelCallEndEvent end && end.getUsage() != null)
                                update(id, t -> ((ObjectNode) t.path("calls")).put(end.getReplyId(),
                                        end.getUsage().getTotalTokens()));
                            if (event instanceof ToolCallEndEvent tool)
                                update(id, t -> t.withArray("sdkToolCalls").add(tool.getToolCallName()));
                        });
                if (mode.equals("cancel-model"))
                    events = events.takeUntilOther(stop.asMono());
                var result = events.filter(AgentResultEvent.class::isInstance)
                        .blockLast(Duration.ofSeconds(30));
                if (result == null && mode.equals("cancel-model")) {
                    update(id, t -> t.put("execution", "sdk-subscription-ended"));
                    return;
                }
                if (result == null) throw new IllegalStateException("missing SDK result");
                String draft = Files.readString(workspace.resolve("draft.md"));
                update(id, t -> t.put("phase", "staged"));
                gate.asMono().block(Duration.ofSeconds(90));
                publish(id, draft, mode);
            }
        } catch (Exception error) {
            update(id, t -> {
                if (running(t)) t.put("state", "FAILED").put("phase", error.toString());
            });
        } finally {
            synchronized (this) {
                gates.remove(id);
                stops.remove(id);
            }
            try {
                Files.deleteIfExists(workspace.resolve("source.txt"));
                Files.deleteIfExists(workspace.resolve("draft.md"));
                Files.deleteIfExists(workspace);
            } catch (IOException error) {
                System.err.println(error);
            }
        }
    }

    synchronized void publish(String id, String draft, String mode) throws IOException {
        ObjectNode task = tasks.get(id).deepCopy();
        if (!running(task)) {
            save(task.put("latePublishRejected", true).put("execution", "worker-returned"));
            return;
        }
        save(task.put("state", "COMPLETED").put("phase", "published").put("document", draft),
                mode.equals("save-fail"));
    }
}
