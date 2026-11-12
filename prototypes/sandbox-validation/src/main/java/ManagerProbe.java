import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.compress.archivers.tar.*;

/** Throwaway fixed-operation manager; no arbitrary image, command, mount or Docker API forwarding. */
public final class ManagerProbe {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final Path REGISTRY = Path.of("/registry");
    static final String KEY = Objects.requireNonNull(System.getenv("MANAGER_KEY"));
    static final Map<String, Lease> LEASES = new ConcurrentHashMap<>();
    static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    static final class Lease {
        final String id;
        final String tokenHash;
        final SandboxProbe.Task task;
        volatile String state = "RUNNING";
        String draft = "";
        boolean cleaned;
        Lease(String id, String tokenHash) {
            this.id = id; this.tokenHash = tokenHash;
            task = new SandboxProbe.Task("managed-" + id, "application");
            task.config.getAdditionalRunArgs().addAll(List.of("--label=iwork.manager=" + SandboxProbe.RUN,
                    "--label=iwork.lease=" + id, "--entrypoint=/opt/task-init", "--rm"));
        }
        synchronized ObjectNode snapshot() {
            return JSON.createObjectNode().put("id", id).put("state", state)
                    .put("draft", draft).put("cleaned", cleaned);
        }
        synchronized void save() throws IOException {
            ObjectNode data = snapshot().put("tokenHash", tokenHash)
                    .put("container", task.id()).put("run", SandboxProbe.RUN);
            Path staging = REGISTRY.resolve(id + ".next");
            JSON.writeValue(staging.toFile(), data);
            Files.move(staging, REGISTRY.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    static void cleanup(Lease lease) throws Exception {
        if (lease.task.id() != null && SandboxProbe.exists(lease.task.id())) lease.task.remove();
        synchronized (lease) { lease.cleaned = true; lease.save(); }
    }
    static void convert(Lease lease) {
        try {
            lease.task.exec("python /opt/worker.py convert", 6);
            String draft = SandboxProbe.draft(lease.task);
            cleanup(lease);
            synchronized (lease) {
                if (lease.state.equals("RUNNING")) { lease.state = "SUCCEEDED"; lease.draft = draft; lease.save(); }
            }
        } catch (Exception | AssertionError error) {
            System.err.println("CONVERT_FAILED " + lease.id + " " + error);
            synchronized (lease) {
                if (lease.state.equals("RUNNING")) lease.state = "FAILED";
            }
            try { cleanup(lease); }
            catch (Exception cleanupError) { System.err.println("CLEANUP_FAILED " + lease.id); }
        }
    }
    static synchronized ObjectNode create(String source) throws Exception {
        if (LEASES.values().stream().filter(l -> l.state.equals("RUNNING")).count() >= 4)
            throw new RejectedExecutionException("capacity");
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        Lease lease = new Lease(id, hash(token));
        LEASES.put(id, lease);
        lease.save(); // Persist intent before creating a container; recovery also scans exact manager label.
        try {
            lease.task.sandbox.start();
            lease.save();
            byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (var tar = new TarArchiveOutputStream(archive)) {
                var entry = new TarArchiveEntry("source.txt"); entry.setSize(bytes.length); entry.setMode(0444);
                tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
            }
            lease.task.sandbox.hydrateWorkspace(new ByteArrayInputStream(archive.toByteArray()));
            WORKERS.submit(() -> convert(lease));
            return JSON.createObjectNode().put("id", id).put("token", token);
        } catch (Exception error) {
            lease.state = "FAILED";
            cleanup(lease);
            throw error;
        }
    }
    static void handle(HttpExchange exchange) throws IOException {
        try {
            if (!equal(exchange.getRequestHeaders().getFirst("Authorization"), "Bearer " + KEY)) {
                respond(exchange, 401, Map.of("error", "unauthorized")); return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (exchange.getRequestURI().getQuery() != null) {
                respond(exchange, 400, Map.of("error", "query not supported")); return;
            }
            if (path.equals("/health") && method.equals("GET")) {
                respond(exchange, 200, Map.of("ready", true)); return;
            }
            if (path.equals("/tasks") && method.equals("POST")) {
                byte[] body = exchange.getRequestBody().readNBytes(32769);
                if (body.length > 32768) { respond(exchange, 413, Map.of("error", "body too large")); return; }
                JsonNode input = JSON.readTree(body);
                if (input == null || !input.isObject() || input.size() != 1 || !input.path("source").isTextual()
                        || input.path("source").asText().isBlank() || input.path("source").asText().length() > 5000) {
                    respond(exchange, 400, Map.of("error", "only a bounded source string is accepted")); return;
                }
                respond(exchange, 202, create(input.path("source").asText())); return;
            }
            if (!path.matches("/tasks/[0-9a-f-]{36}") || !(method.equals("GET") || method.equals("DELETE"))) {
                respond(exchange, 404, Map.of("error", "not found")); return;
            }
            Lease lease = LEASES.get(path.substring(7));
            String token = exchange.getRequestHeaders().getFirst("X-Task-Token");
            if (lease == null || token == null || !equal(lease.tokenHash, hash(token))) {
                respond(exchange, 404, Map.of("error", "not found")); return;
            }
            if (method.equals("DELETE")) {
                synchronized (lease) {
                    if (lease.state.equals("RUNNING")) { lease.state = "CANCELLED"; lease.save(); }
                }
                cleanup(lease);
            }
            respond(exchange, 200, lease.snapshot());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            respond(exchange, 400, Map.of("error", "invalid JSON"));
        } catch (RejectedExecutionException error) {
            respond(exchange, 429, Map.of("error", "capacity"));
        } catch (Exception error) {
            System.err.println("MANAGER_ERROR " + error.getClass().getSimpleName());
            respond(exchange, 500, Map.of("error", "operation failed"));
        } finally { exchange.close(); }
    }
    static void recover() throws Exception {
        Files.createDirectories(REGISTRY);
        String ids = SandboxProbe.cli("docker", "ps", "-aq", "--filter", "label=iwork.manager=" + SandboxProbe.RUN);
        for (String id : ids.split("\\s+")) {
            if (!id.isBlank()) SandboxProbe.cli("docker", "rm", "-f", id);
        }
        try (var files = Files.list(REGISTRY)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode data = JSON.readTree(path.toFile());
                SandboxProbe.check(data.path("run").asText().equals(SandboxProbe.RUN), "registry run mismatch");
                Lease lease = new Lease(data.path("id").asText(), data.path("tokenHash").asText());
                lease.state = data.path("state").asText().equals("RUNNING") ? "INTERRUPTED" : data.path("state").asText();
                lease.draft = data.path("draft").asText(); lease.cleaned = true;
                lease.save(); LEASES.put(lease.id, lease);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        SandboxProbe.check(!KEY.isBlank(), "empty manager key");
        recover();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 16);
        server.createContext("/", ManagerProbe::handle);
        server.setExecutor(WORKERS); server.start();
        System.out.println("MANAGER_READY");
    }
}
