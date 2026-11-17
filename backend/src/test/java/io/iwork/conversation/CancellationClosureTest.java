package io.iwork.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/cancellation-closure-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
public class CancellationClosureTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.7")
                    .withUrlParam("connectTimeout", "3000")
                    .withUrlParam("socketTimeout", "3000");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;

        HttpResponse<String> request(String method, String path, String body, boolean token)
                throws Exception {
            var builder =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(java.time.Duration.ofSeconds(15));
            if (token && csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (body != null)
                builder.header(
                        "Content-Type",
                        path.endsWith("/login")
                                ? "application/x-www-form-urlencoded"
                                : "application/json");
            return client.send(
                    builder.method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            csrf =
                    json.readTree(request("GET", "/api/auth/csrf", null, false).body())
                            .get("token")
                            .asString();
        }

        HttpResponse<String> login(String username, String password) throws Exception {
            csrf();
            return request(
                    "POST",
                    "/api/auth/login",
                    "username=" + username + "&password=" + password,
                    true);
        }
    }

    record Waiting(
            TaskRunner.Input input, String reply, java.util.concurrent.CountDownLatch gate) {}

    static final java.util.concurrent.ConcurrentMap<String, Waiting> waiting =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicInteger stopped =
            new java.util.concurrent.atomic.AtomicInteger();

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @org.springframework.beans.factory.annotation.Autowired RemoteReceipts remote;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    public static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean
        RemoteReceipts receipts() throws Exception {
            return new RemoteReceipts();
        }

        @org.springframework.context.annotation.Bean
        Delivery delivery(TaskService tasks, RemoteReceipts receipts) {
            return new Delivery(tasks, receipts);
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        TaskRunner controlledRunner(
                io.iwork.agent.HarnessTaskRunner runner, io.iwork.model.GovernedModel models) {
            return input -> {
                if (input.message().equals("summary") || input.message().equals("overflow")) {
                    var message =
                            io.agentscope.core.message.Msg.builder()
                                    .role(io.agentscope.core.message.MsgRole.USER)
                                    .textContent("output")
                                    .build();
                    models
                            .forTask(
                                    input,
                                    input.message().equals("summary") ? "SUMMARY" : "OVERFLOW")
                            .stream(List.of(message), null, null)
                            .blockLast();
                    return "unreachable";
                }
                String reply = runner.execute(input);
                if (input.message().equals("prepared") || input.message().equals("race")) {
                    var pending =
                            new Waiting(input, reply, new java.util.concurrent.CountDownLatch(1));
                    waiting.put(input.taskId(), pending);
                    pending.gate().await();
                }
                return reply;
            };
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport providerFixture() {
            return new io.agentscope.core.model.transport.HttpTransport() {
                final ObjectMapper json = new ObjectMapper();

                public io.agentscope.core.model.transport.HttpResponse execute(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    throw new AssertionError("stream only");
                }

                public reactor.core.publisher.Flux<String> stream(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    String fault = "";
                    for (var message : json.readTree(request.getBody()).path("messages"))
                        if (message.path("role").asString().equals("user"))
                            fault = message.path("content").asString();
                    if (fault.equals("before"))
                        return reactor.core.publisher.Flux.<String>never()
                                .doOnCancel(stopped::incrementAndGet);
                    if (fault.equals("known"))
                        return reactor.core.publisher.Flux.just(
                                        "{\"id\":\"recovery-known\",\"usage\":{\"prompt_tokens\":23,\"completion_tokens\":11},\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"已观察输出\"},\"finish_reason\":null}]}")
                                .concatWith(reactor.core.publisher.Flux.never());
                    if ("true".equals(System.getenv("IWORK_TEST_SUMMARY_CRASH"))
                            && fault.contains("Context Extraction Assistant")
                            && fault.contains("summary-crash"))
                        return reactor.core.publisher.Flux.<String>never()
                                .doOnCancel(stopped::incrementAndGet);
                    if (fault.equals("output"))
                        return reactor.core.publisher.Flux.just(
                                        "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"已观察输出\"},\"finish_reason\":null}]}")
                                .concatWith(reactor.core.publisher.Flux.never())
                                .doOnCancel(stopped::incrementAndGet);
                    var body = json.readTree(request.getBody());
                    var names = new HashSet<String>();
                    for (var tool : body.path("tools"))
                        names.add(tool.path("function").path("name").asString());
                    String user = "";
                    for (var message : body.path("messages"))
                        if (message.path("role").asString().equals("user"))
                            user = message.path("content").asString();
                    if (!names.contains("prepare_document")) {
                        String reply =
                                user.contains("Context Extraction Assistant")
                                        ? "蓝鲸项目，周五交付；已保存项目计划，按选定来源版本继续。"
                                        : "已记录";
                        return reactor.core.publisher.Flux.just(
                                json.writeValueAsString(
                                        Map.of(
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "index",
                                                                0,
                                                                "delta",
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        reply),
                                                                "finish_reason",
                                                                "stop")))),
                                "[DONE]");
                    }
                    boolean source = names.contains("read_document_version"),
                            read = false,
                            prepared = false;
                    for (var message : body.path("messages"))
                        if (message.path("role").asString().equals("tool")) {
                            if (message.path("content").asString().contains("PREPARED"))
                                prepared = true;
                            else read = true;
                        }
                    Map<String, Object> delta;
                    String finish;
                    if (prepared) {
                        delta = Map.of("role", "assistant", "content", "文档已准备");
                        finish = "stop";
                    } else {
                        String tool =
                                !read && names.contains("read_task_material")
                                        ? "read_task_material"
                                        : source && !read
                                                ? "read_document_version"
                                                : "prepare_document";
                        var matcher =
                                java.util.regex.Pattern.compile("id=([a-f0-9-]{36})")
                                        .matcher(request.getBody());
                        String material = matcher.find() ? matcher.group(1) : "";
                        var args =
                                tool.equals("read_task_material")
                                        ? Map.of("materialId", material, "offset", 0)
                                        : tool.equals("read_document_version")
                                                ? Map.of("offset", 0)
                                                : Map.of(
                                                        "title",
                                                        "项目计划",
                                                        "markdown",
                                                        source
                                                                ? "# 项目计划\n蓝鲸项目，周五交付。\n补充执行步骤。\n"
                                                                : "# 项目计划\n蓝鲸项目，周五交付。\n");
                        if (source && read) assertThat(request.getBody()).contains("蓝鲸项目");
                        delta =
                                Map.of(
                                        "role",
                                        "assistant",
                                        "tool_calls",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "id",
                                                        UUID.randomUUID().toString(),
                                                        "type",
                                                        "function",
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                tool,
                                                                "arguments",
                                                                json.writeValueAsString(args)))));
                        finish = "tool_calls";
                    }

                    return reactor.core.publisher.Flux.just(
                            json.writeValueAsString(
                                    Map.of(
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "delta",
                                                            delta,
                                                            "finish_reason",
                                                            finish)))),
                            "[DONE]");
                }

                public void close() {}
            };
        }
    }

    static class RemoteReceipts implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger queries =
                new java.util.concurrent.atomic.AtomicInteger();

        RemoteReceipts() throws Exception {
            server =
                    com.sun.net.httpserver.HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        queries.incrementAndGet();
                        var saved = waiting.get(exchange.getRequestURI().getPath().substring(1));
                        byte[] bytes =
                                new ObjectMapper()
                                        .writeValueAsBytes(
                                                Map.of(
                                                        "status",
                                                        "COMPLETED",
                                                        "result",
                                                        saved.reply()));
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
        }

        String query(String id) throws Exception {
            var response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + server.getAddress().getPort()
                                                                    + "/"
                                                                    + id))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Remote receipt failed");
            return new ObjectMapper().readTree(response.body()).path("result").asString();
        }

        public void close() {
            server.stop(0);
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @org.springframework.web.bind.annotation.RestController
    static class Delivery {
        final TaskService tasks;
        final RemoteReceipts remote;

        Delivery(TaskService tasks, RemoteReceipts remote) {
            this.tasks = tasks;
            this.remote = remote;
        }

        @org.springframework.web.bind.annotation.GetMapping("/api/test/ready/{id}")
        Map<String, Boolean> ready(
                @org.springframework.security.core.annotation.AuthenticationPrincipal
                        io.iwork.identity.Caller caller,
                @org.springframework.web.bind.annotation.PathVariable String id) {
            tasks.get(caller, id);
            return Map.of("ready", waiting.containsKey(id));
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/test/release/{id}")
        void release(
                @org.springframework.security.core.annotation.AuthenticationPrincipal
                        io.iwork.identity.Caller caller,
                @org.springframework.web.bind.annotation.PathVariable String id) {
            tasks.get(caller, id);
            waiting.get(id).gate().countDown();
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/test/deliver/{id}")
        void deliver(
                @org.springframework.security.core.annotation.AuthenticationPrincipal
                        io.iwork.identity.Caller caller,
                @org.springframework.web.bind.annotation.PathVariable String id)
                throws Exception {
            tasks.get(caller, id);
            String reply = remote.query(id);
            tasks.acceptResult(waiting.get(id).input(), reply);
        }
    }

    @Test
    void cancellationAndCommitArbitrateLateRemoteResultsWithoutLosingVersions() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Cancel-password-19\"}",
                true);
        admin.login("admin", "Cancel-password-19");
        admin.csrf();
        String model =
                json.readTree(
                                admin.request(
                                                "POST",
                                                "/api/admin/models",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Fixture",
                                                                "baseUrl",
                                                                "https://models.example.test/v1",
                                                                "modelName",
                                                                "writer",
                                                                "credentialRef",
                                                                "IWORK_MODEL_KEY_TEST",
                                                                "inputPrice",
                                                                "1",
                                                                "outputPrice",
                                                                "2",
                                                                "enabled",
                                                                true,
                                                                "contextWindow",
                                                                131072)),
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        String uid =
                json.readTree(admin.request("GET", "/api/auth/me", null, false).body())
                        .path("id")
                        .asString();
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(model))),
                true);
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"5\"}", true);
        String cid = conversation(admin);
        String initial = start(admin, cid, model, null, "normal");
        await(admin, initial, "COMPLETED");
        var original =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/documents",
                                                null,
                                                false)
                                        .body())
                        .get(0);
        String base = original.path("versionId").asString(),
                artifact = original.path("id").asString();
        String bytes =
                admin.request("GET", "/api/document-versions/" + base + "/content", null, false)
                        .body();
        for (String fault : List.of("before", "output", "summary", "overflow")) {
            String task = start(admin, cid, model, base, fault);
            awaitCall(admin, task);
            int previous = stopped.get();
            assertThat(
                            admin.request("POST", "/api/tasks/" + task + "/cancel", "{}", true)
                                    .statusCode())
                    .isEqualTo(200);
            await(admin, task, "CANCELLED");
            assertThat(stopped.get()).isGreaterThan(previous);
            var calls =
                    json.readTree(
                            admin.request("GET", "/api/tasks/" + task + "/calls", null, false)
                                    .body());
            assertThat(calls.size()).isEqualTo(1);
            assertThat(calls.get(0).path("status").asString()).isEqualTo("CLOSED");
            assertThat(calls.get(0).path("inputTokens").isNull()).isTrue();
            assertThat(new java.math.BigDecimal(calls.get(0).path("charged").asString()))
                    .isPositive();
            assertThat(
                            json.readTree(admin.request("GET", "/api/budget", null, false).body())
                                    .path("reserved")
                                    .asString())
                    .isEqualTo("0.000000");
        }
        String late = start(admin, cid, model, base, "prepared");
        awaitPrepared(admin, late);
        admin.request("POST", "/api/tasks/" + late + "/cancel", "{}", true);
        await(admin, late, "CANCELLED");
        for (int i = 0; i < 2; i++)
            assertThat(admin.request("POST", "/api/test/deliver/" + late, "{}", true).statusCode())
                    .isEqualTo(409);
        assertThat(remote.queries.get()).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM document_versions WHERE document_id=?",
                                Integer.class,
                                artifact))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts c JOIN tasks t ON t.id=c.task_id WHERE c.task_id=? AND t.status='COMPLETED'",
                                Integer.class,
                                late))
                .isZero();
        for (int i = 0; i < 6; i++) {
            String race = start(admin, cid, model, base, "race");
            awaitPrepared(admin, race);
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var cancel =
                        pool.submit(
                                () ->
                                        admin.request(
                                                "POST",
                                                "/api/tasks/" + race + "/cancel",
                                                "{}",
                                                true));
                var release =
                        pool.submit(
                                () ->
                                        admin.request(
                                                "POST", "/api/test/release/" + race, "{}", true));
                cancel.get();
                release.get();
            }
            String status = await(admin, race, null);
            assertThat(status).isIn("COMPLETED", "CANCELLED");
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM document_versions WHERE task_id=?",
                                    Integer.class,
                                    race))
                    .isEqualTo(status.equals("COMPLETED") ? 1 : 0);
        }
        String completed = start(admin, cid, model, base, "normal");
        await(admin, completed, "COMPLETED");
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "POST",
                                                        "/api/tasks/" + completed + "/cancel",
                                                        "{}",
                                                        true)
                                                .body())
                                .path("status")
                                .asString())
                .isEqualTo("COMPLETED");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + base + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(bytes);
    }

    String conversation(Browser browser) throws Exception {
        return json.readTree(browser.request("POST", "/api/conversations", "{}", true).body())
                .path("id")
                .asString();
    }

    String start(Browser browser, String cid, String model, String base, String message)
            throws Exception {
        var input =
                new HashMap<String, Object>(
                        Map.of(
                                "requestId",
                                UUID.randomUUID().toString(),
                                "modelId",
                                model,
                                "message",
                                message,
                                "outputMode",
                                "DOCUMENT"));
        if (base != null) input.put("baseVersionId", base);
        var response =
                browser.request(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(input),
                        true);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("id").asString();
    }

    String await(Browser browser, String id, String expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            var state =
                    json.readTree(browser.request("GET", "/api/tasks/" + id, null, false).body());
            if (state.path("executionState").asString().equals("STOPPED")) {
                if (expected != null)
                    assertThat(state.path("status").asString())
                            .describedAs(state.toString())
                            .isEqualTo(expected);
                return state.path("status").asString();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not finish");
    }

    void awaitCall(Browser browser, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var calls =
                    json.readTree(
                            browser.request("GET", "/api/tasks/" + id + "/calls", null, false)
                                    .body());
            if (!calls.isEmpty()
                    && db.queryForObject(
                                    "SELECT COUNT(*) FROM model_calls WHERE task_id=? AND sent_at IS NOT NULL",
                                    Integer.class,
                                    id)
                            > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("No actual provider call");
    }

    void awaitPrepared(Browser browser, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (json.readTree(browser.request("GET", "/api/test/ready/" + id, null, false).body())
                    .path("ready")
                    .asBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Document was not prepared");
    }
}
