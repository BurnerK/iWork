package io.iwork.sandbox;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Separate privileged process; deliberately does not bootstrap Spring or use business credentials.
 */
public final class SandboxControl {
    static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    static final int LIMIT = 2 * 1024 * 1024, TTL = 60;
    static final String INSTANCE = required("IWORK_SANDBOX_INSTANCE", "[a-zA-Z0-9_.:-]{1,160}"),
            LABEL = "iwork.sandbox=" + INSTANCE;
    static final Path HEALTH = Path.of("/watchdog/health.json"), REGISTRY = Path.of("/registry");
    static final Map<String, Job> jobs = new ConcurrentHashMap<>();
    static final Object admission = new Object();
    static String key;
    static volatile boolean ready;

    static String required(String name, String pattern) {
        var value = System.getenv(name);
        if (value == null || !value.matches(pattern))
            throw new IllegalStateException("Missing or invalid " + name);
        return value;
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean equal(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String uuid(JsonNode body, String field) {
        var value = body.path(field).asText("");
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException();
        return value;
    }

    static void save(Path path, Object value, boolean durable) throws IOException {
        var next = path.resolveSibling(path.getFileName() + ".next");
        Files.write(next, JSON.writeValueAsBytes(value));
        if (durable) {
            try (var channel = FileChannel.open(next, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        Files.move(next, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (durable) {
            try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    record Command(int code, String output) {}

    static Command docker(byte[] input, int seconds, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("/usr/local/bin/docker");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var read =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                byte[] bytes = process.getInputStream().readNBytes(16 * LIMIT + 1);
                                if (bytes.length > 16 * LIMIT) {
                                    process.destroyForcibly();
                                    throw new IOException("Output limit");
                                }
                                return new String(bytes, StandardCharsets.UTF_8);
                            } catch (IOException failure) {
                                throw new CompletionException(failure);
                            }
                        });
        var write =
                CompletableFuture.runAsync(
                        () -> {
                            try (var stream = process.getOutputStream()) {
                                if (input != null) stream.write(input);
                            } catch (IOException failure) {
                                throw new CompletionException(failure);
                            }
                        });
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS))
                throw new IOException("Docker deadline");
            write.get(2, TimeUnit.SECONDS);
            return new Command(process.exitValue(), read.get(2, TimeUnit.SECONDS).trim());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static String docker(String... args) throws Exception {
        var result = docker(null, 3, args);
        if (result.code() != 0) throw new IOException("Docker unavailable");
        return result.output();
    }

    static List<String> containers() throws Exception {
        var value = docker("ps", "-aq", "--filter", "label=" + LABEL);
        return value.isBlank() ? List.of() : List.of(value.split("\\s+"));
    }

    static boolean exists(String name) throws Exception {
        return !docker("ps", "-aq", "--filter", "name=^/" + name + "$").isBlank();
    }

    static void remove(String name) throws Exception {
        if (exists(name)) {
            var info = JSON.readTree(docker("inspect", name)).get(0);
            if (!INSTANCE.equals(info.path("Config").path("Labels").path("iwork.sandbox").asText()))
                throw new IOException("Foreign container");
            docker("rm", "-f", name);
        }
        if (exists(name)) throw new IOException("Removal unconfirmed");
    }

    static boolean supervised() {
        try {
            var health = JSON.readTree(Files.readAllBytes(HEALTH));
            long age = System.currentTimeMillis() - health.path("at").asLong();
            return INSTANCE.equals(health.path("instance").asText())
                    && age >= 0
                    && age <= 2000
                    && health.path("ttl").asInt() == TTL;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static void watchdog() throws Exception {
        while (true) {
            try {
                for (var id : containers()) {
                    var info = JSON.readTree(docker("inspect", id)).get(0);
                    if (!INSTANCE.equals(
                            info.path("Config").path("Labels").path("iwork.sandbox").asText()))
                        throw new IOException("Foreign container");
                    var created = Instant.parse(info.path("Created").asText());
                    if (Duration.between(created, Instant.now()).getSeconds() >= TTL) {
                        long expiredAt = System.currentTimeMillis();
                        remove(info.path("Name").asText().substring(1));
                        System.out.println(
                                JSON.writeValueAsString(
                                        Map.of(
                                                "event",
                                                "sandbox_expired_removed",
                                                "instance",
                                                INSTANCE,
                                                "container",
                                                info.path("Id").asText(),
                                                "createdAt",
                                                created.toEpochMilli(),
                                                "expiredAt",
                                                expiredAt,
                                                "confirmedAt",
                                                System.currentTimeMillis())));
                    }
                }
                // Admission only trusts fresh heartbeats, so they do not need crash durability.
                save(
                        HEALTH,
                        Map.of("instance", INSTANCE, "at", System.currentTimeMillis(), "ttl", TTL),
                        false);
            } catch (Exception failure) {
                Files.deleteIfExists(HEALTH);
                System.err.println("Watchdog supervision unavailable");
            }
            Thread.sleep(500);
        }
    }

    public static final class Job {
        public String id, taskId, materialId, tokenHash, requestHash, name, containerId, error;
        public volatile String status = "RUNNING", cleanup = "UNKNOWN";
        public boolean hasText;

        public Job() {}

        void save() throws IOException {
            SandboxControl.save(REGISTRY.resolve(id + ".json"), this, true);
        }

        Map<String, Object> view() throws IOException {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", id);
            view.put("taskId", taskId);
            view.put("materialId", materialId);
            view.put("status", status);
            view.put("cleanup", cleanup);
            view.put("error", error);
            view.put("text", hasText ? Files.readString(REGISTRY.resolve(id + ".text")) : null);
            return view;
        }

        void saveText(String text) throws IOException {
            Path path = REGISTRY.resolve(id + ".text"), next = REGISTRY.resolve(id + ".text.next");
            Files.writeString(next, text);
            try (var channel = FileChannel.open(next, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    next,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }

        synchronized void clean() throws Exception {
            cleanup = "UNKNOWN";
            save();
            remove(name);
            cleanup = "CONFIRMED";
            save();
        }

        void execute(byte[] source) {
            try {
                var output = docker(source, 30, "start", "-ai", name);
                var body = JSON.readTree(output.output());
                var parsed = body.path("text");
                if (output.code() != 0
                        || !parsed.isTextual()
                        || parsed.asText().getBytes(StandardCharsets.UTF_8).length > LIMIT)
                    throw new IOException("Invalid text");
                synchronized (this) {
                    clean();
                    if (status.equals("RUNNING")) {
                        saveText(parsed.asText());
                        hasText = true;
                        status = "COMPLETED";
                        save();
                    }
                }
            } catch (Exception failure) {
                synchronized (this) {
                    if (status.equals("RUNNING")) {
                        status = "FAILED";
                        error = "material_parse_failed";
                    }
                    try {
                        save();
                        clean();
                    } catch (Exception unavailable) {
                        System.err.println("Sandbox cleanup pending: " + id);
                    }
                }
            }
        }
    }

    static Job create(JsonNode body, String token) throws Exception {
        if (!body.isObject()
                || body.size() != 5
                || !body.has("content")
                || !body.has("operation")
                || !"parse_text".equals(body.path("operation").asText()))
            throw new IllegalArgumentException();
        String id = uuid(body, "id"),
                task = uuid(body, "taskId"),
                material = uuid(body, "materialId");
        if (token == null || !token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        byte[] source = Base64.getDecoder().decode(body.path("content").asText());
        if (source.length == 0 || source.length > LIMIT) throw new IllegalArgumentException();
        String digest = hash(task + material + body.path("content").asText());
        synchronized (admission) {
            var old = jobs.get(id);
            if (old != null) {
                if (!equal(old.tokenHash, hash(token))) throw new SecurityException();
                if (!old.taskId.equals(task)
                        || !old.materialId.equals(material)
                        || old.requestHash != null && !old.requestHash.equals(digest))
                    throw new IllegalArgumentException();
                return old;
            }
            boolean supervisionReady = supervised();
            boolean pendingCleanup =
                    jobs.values().stream()
                            .anyMatch(
                                    j ->
                                            !j.status.equals("RUNNING")
                                                    && !j.cleanup.equals("CONFIRMED"));
            if (!ready || !supervisionReady || pendingCleanup) {
                System.err.println(
                        "Sandbox admission unavailable: ready="
                                + ready
                                + " supervised="
                                + supervisionReady
                                + " pendingCleanup="
                                + pendingCleanup);
                throw new IOException("Supervision unavailable");
            }
            if (jobs.values().stream().filter(j -> j.status.equals("RUNNING")).count() >= 2)
                throw new RejectedExecutionException();
            var job = new Job();
            job.id = id;
            job.taskId = task;
            job.materialId = material;
            job.tokenHash = hash(token);
            job.requestHash = digest;
            job.name = "iwork-" + INSTANCE + "-" + id;
            job.save();
            jobs.put(id, job);
            synchronized (job) {
                try {
                    job.containerId =
                            docker(
                                    "create",
                                    "--name",
                                    job.name,
                                    "--label",
                                    LABEL,
                                    "--label",
                                    "iwork.job=" + id,
                                    "--network",
                                    "none",
                                    "--user",
                                    "1000:1000",
                                    "--read-only",
                                    "--cap-drop",
                                    "ALL",
                                    "--security-opt",
                                    "no-new-privileges",
                                    "--pids-limit",
                                    "32",
                                    "--cpus",
                                    "0.5",
                                    "--memory",
                                    "64m",
                                    "--memory-swap",
                                    "64m",
                                    "--tmpfs",
                                    "/tmp/codex:rw,noexec,nosuid,size=4m,uid=1000,gid=1000,mode=0700",
                                    "--log-driver",
                                    "none",
                                    "-i",
                                    required(
                                            "IWORK_SANDBOX_IMAGE",
                                            "[a-zA-Z0-9][a-zA-Z0-9_.:/@-]{0,511}"));
                    job.save();
                    Thread.ofVirtual().start(() -> job.execute(source));
                    return job;
                } catch (Exception failure) {
                    System.err.println("Sandbox container creation unavailable");
                    job.status = "FAILED";
                    job.error = "sandbox_unavailable";
                    try {
                        job.save();
                        job.clean();
                    } catch (Exception unavailable) {
                        System.err.println("Sandbox creation cleanup pending: " + id);
                    }
                    throw failure;
                }
            }
        }
    }

    static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void handle(HttpExchange exchange) throws IOException {
        try {
            if (!equal(exchange.getRequestHeaders().getFirst("Authorization"), "Bearer " + key)) {
                respond(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            var path = exchange.getRequestURI().getRawPath();
            var method = exchange.getRequestMethod();
            if (exchange.getRequestURI().getRawQuery() != null)
                throw new IllegalArgumentException();
            if (path.equals("/health") && method.equals("GET")) {
                respond(
                        exchange,
                        supervised() && ready ? 200 : 503,
                        Map.of("ready", supervised() && ready));
                return;
            }
            var token = exchange.getRequestHeaders().getFirst("X-Task-Token");
            if (path.equals("/tasks") && method.equals("POST")) {
                byte[] bytes = exchange.getRequestBody().readNBytes(3 * LIMIT + 1);
                if (bytes.length > 3 * LIMIT) throw new IllegalArgumentException();
                var job = create(JSON.readTree(bytes), token);
                synchronized (job) {
                    respond(exchange, 200, job.view());
                }
                return;
            }
            if (!path.matches("/tasks/[0-9a-f-]{36}")) {
                respond(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            String id = path.substring(7);
            Job job;
            synchronized (admission) {
                job = jobs.get(id);
                if (job == null && method.equals("DELETE")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(1025);
                    if (bytes.length > 1024 || token == null || !token.matches("[a-f0-9]{64}"))
                        throw new IllegalArgumentException();
                    var body = JSON.readTree(bytes);
                    if (!body.isObject() || body.size() != 2) throw new IllegalArgumentException();
                    job = new Job();
                    job.id = id;
                    job.taskId = uuid(body, "taskId");
                    job.materialId = uuid(body, "materialId");
                    job.name = "iwork-" + INSTANCE + "-" + id;
                    job.tokenHash = hash(token);
                    job.status = "CANCELLED";
                    job.error = "cancelled";
                    job.save();
                    jobs.put(id, job);
                }
            }
            if (job == null || token == null || !equal(job.tokenHash, hash(token))) {
                respond(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            synchronized (job) {
                if (method.equals("DELETE")) {
                    if (job.status.equals("RUNNING")) {
                        job.status = "CANCELLED";
                        job.error = "cancelled";
                        job.save();
                    }
                    job.clean();
                } else if (!method.equals("GET")) throw new IllegalArgumentException();
                respond(exchange, 200, job.view());
            }
        } catch (SecurityException failure) {
            respond(exchange, 404, Map.of("error", "not_found"));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException failure) {
            respond(exchange, 400, Map.of("error", "invalid_request"));
        } catch (RejectedExecutionException failure) {
            respond(exchange, 429, Map.of("error", "sandbox_capacity"));
        } catch (Exception failure) {
            respond(exchange, 503, Map.of("error", "sandbox_unavailable"));
        } finally {
            exchange.close();
        }
    }

    static void recover() throws Exception {
        Files.createDirectories(REGISTRY);
        try (var files = Files.list(REGISTRY)) {
            for (var path :
                    files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                            .toList()) {
                var job = JSON.readValue(Files.readAllBytes(path), Job.class);
                if (!job.name.equals("iwork-" + INSTANCE + "-" + job.id))
                    throw new IOException("Registry instance mismatch");
                boolean interrupted = job.status.equals("RUNNING");
                if (interrupted) {
                    job.status = "INTERRUPTED";
                    job.error = "manager_restarted";
                }
                jobs.put(job.id, job);
                if (interrupted || !job.cleanup.equals("CONFIRMED")) job.clean();
            }
        }
        for (var id : containers()) {
            var info = JSON.readTree(docker("inspect", id)).get(0);
            remove(info.path("Name").asText().substring(1));
        }
        ready = true;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("watchdog")) {
            watchdog();
            return;
        }
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        System.setProperty("jdk.httpserver.maxConnections", "64");
        key = Files.readString(Path.of("/run/secrets/sandbox_key")).trim();
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalStateException("Invalid service key");
        Thread.ofVirtual()
                .start(
                        () -> {
                            while (!ready) {
                                try {
                                    recover();
                                } catch (Exception unavailable) {
                                    System.err.println("Sandbox recovery pending");
                                }
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException done) {
                                    return;
                                }
                            }
                            while (true) {
                                for (var job : jobs.values())
                                    if (!job.status.equals("RUNNING")
                                            && !job.cleanup.equals("CONFIRMED")) {
                                        try {
                                            job.clean();
                                        } catch (Exception unavailable) {
                                            System.err.println(
                                                    "Sandbox cleanup pending: " + job.id);
                                        }
                                    }
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException done) {
                                    return;
                                }
                            }
                        });
        var server = HttpServer.create(new java.net.InetSocketAddress("0.0.0.0", 8090), 16);
        server.createContext("/", SandboxControl::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }
}
