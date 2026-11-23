package io.iwork.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.iwork.ApiTestResponse;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
@EnabledIfEnvironmentVariable(named = "IWORK_SANDBOX_TEST_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/parsing-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-parse-key",
            "IWORK_MODEL_URL_TEST=https://models.example.test/v1",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-16"
        })
class MaterialParsingTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);

    static final java.util.concurrent.atomic.AtomicInteger parseRequests =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicBoolean rejectAdmission =
            new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicBoolean rejectCleanup =
            new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicBoolean holdAdmission =
            new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.Semaphore admitParse = new java.util.concurrent.Semaphore(0);
    static final HttpClient proxyClient = HttpClient.newHttpClient();
    static final java.util.concurrent.ExecutorService proxyExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    static com.sun.net.httpserver.HttpServer proxy;

    static synchronized String sandboxProxy() {
        if (proxy == null) {
            try {
                proxy =
                        com.sun.net.httpserver.HttpServer.create(
                                new InetSocketAddress("127.0.0.1", 0), 0);
                proxy.setExecutor(proxyExecutor);
                proxy.createContext(
                        "/",
                        exchange -> {
                            try (exchange) {
                                if (exchange.getRequestMethod().equals("POST"))
                                    parseRequests.incrementAndGet();
                                if (exchange.getRequestMethod().equals("POST")
                                        && holdAdmission.compareAndSet(true, false)
                                        && !admitParse.tryAcquire(
                                                8, java.util.concurrent.TimeUnit.SECONDS)) {
                                    exchange.sendResponseHeaders(503, -1);
                                    return;
                                }
                                if ((exchange.getRequestMethod().equals("POST")
                                                && rejectAdmission.compareAndSet(true, false))
                                        || (exchange.getRequestMethod().equals("DELETE")
                                                && rejectCleanup.compareAndSet(true, false))) {
                                    exchange.sendResponseHeaders(503, -1);
                                    return;
                                }
                                var request =
                                        HttpRequest.newBuilder(
                                                URI.create(
                                                        System.getenv("IWORK_SANDBOX_TEST_URL")
                                                                + exchange.getRequestURI()));
                                for (var header :
                                        List.of("Authorization", "X-Task-Token", "Content-Type")) {
                                    var value = exchange.getRequestHeaders().getFirst(header);
                                    if (value != null) request.header(header, value);
                                }
                                var response =
                                        proxyClient.send(
                                                request.method(
                                                                exchange.getRequestMethod(),
                                                                HttpRequest.BodyPublishers
                                                                        .ofByteArray(
                                                                                exchange.getRequestBody()
                                                                                        .readAllBytes()))
                                                        .build(),
                                                HttpResponse.BodyHandlers.ofByteArray());
                                if (response.statusCode() != 200)
                                    System.err.println(
                                            "Sandbox fixture upstream "
                                                    + exchange.getRequestMethod()
                                                    + " status="
                                                    + response.statusCode());
                                exchange.getResponseHeaders()
                                        .set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(
                                        response.statusCode(), response.body().length);
                                exchange.getResponseBody().write(response.body());
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            } catch (java.io.IOException failure) {
                                System.err.println("Sandbox fixture proxy I/O unavailable");
                                throw failure;
                            }
                        });
                proxy.start();
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        return "http://127.0.0.1:" + proxy.getAddress().getPort();
    }

    @org.junit.jupiter.api.AfterAll
    static void closeProxy() {
        if (proxy != null) proxy.stop(0);
        proxyClient.close();
        proxyExecutor.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("iwork.sandbox-url", MaterialParsingTest::sandboxProxy);
        r.add("iwork.sandbox-key-file", () -> System.getenv("IWORK_SANDBOX_TEST_KEY_FILE"));
    }

    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    static final reactor.core.publisher.Sinks.Empty<Void> fileGate =
            reactor.core.publisher.Sinks.empty();
    static final reactor.core.publisher.Sinks.Empty<Void> pauseGate =
            reactor.core.publisher.Sinks.empty();
    static final java.util.concurrent.CountDownLatch pauseReceived =
            new java.util.concurrent.CountDownLatch(1);
    static final java.util.concurrent.CountDownLatch holdReceived =
            new java.util.concurrent.CountDownLatch(1);
    static final java.util.concurrent.CountDownLatch fileReceived =
            new java.util.concurrent.CountDownLatch(1);

    class Browser {
        final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        String csrf;

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var builder =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(java.time.Duration.ofSeconds(15));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
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
                    ApiTestResponse.body());
        }

        void login(String user, String pass) throws Exception {
            csrf =
                    json.readTree(send("GET", "/api/auth/csrf", null).body())
                            .get("token")
                            .asString();
            assertThat(
                            send(
                                            "POST",
                                            "/api/auth/login",
                                            "username=" + user + "&password=" + pass)
                                    .statusCode())
                    .isEqualTo(200);
            csrf =
                    json.readTree(send("GET", "/api/auth/csrf", null).body())
                            .get("token")
                            .asString();
        }

        String upload(byte[] bytes) throws Exception {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:" + port + "/api/materials"))
                                    .header("X-CSRF-TOKEN", csrf)
                                    .header("Content-Type", "application/octet-stream")
                                    .header("X-File-Name", "source.md")
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                    .build(),
                            ApiTestResponse.body());
            assertThat(response.statusCode()).isEqualTo(201);
            return json.readTree(response.body()).get("id").asString();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport parsingFixture() {
            return new io.agentscope.core.model.transport.HttpTransport() {
                final ObjectMapper json = new ObjectMapper();

                public io.agentscope.core.model.transport.HttpResponse execute(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    throw new AssertionError();
                }

                public reactor.core.publisher.Flux<String> stream(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    var body = json.readTree(request.getBody());
                    var toolNames = new java.util.HashSet<String>();
                    for (var tool : body.get("tools"))
                        toolNames.add(tool.path("function").path("name").asString());
                    boolean documentMode = toolNames.contains("prepare_document");
                    assertThat(toolNames)
                            .isEqualTo(
                                    documentMode
                                            ? Set.of("read_task_material", "prepare_document")
                                            : Set.of("read_task_material"));
                    boolean toolResult = false;
                    for (var message : body.get("messages"))
                        if (message.path("role").asString().equals("tool")) toolResult = true;
                    String chunk;
                    if (!toolResult) {
                        var matcher =
                                java.util.regex.Pattern.compile("id=([a-f0-9-]{36})")
                                        .matcher(request.getBody());
                        assertThat(matcher.find()).isTrue();
                        String id = matcher.group(1);
                        if (request.getBody().contains("foreign-reference"))
                            id = "00000000-0000-0000-0000-000000000000";
                        var arguments =
                                json.writeValueAsString(Map.of("materialId", id, "offset", 0));
                        var calls = new ArrayList<Map<String, Object>>();
                        calls.add(
                                Map.of(
                                        "index",
                                        0,
                                        "id",
                                        "parse-one",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "read_task_material",
                                                "arguments",
                                                arguments)));
                        if (request.getBody().contains("duplicate-material"))
                            calls.add(
                                    Map.of(
                                            "index",
                                            1,
                                            "id",
                                            "parse-two",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    "read_task_material",
                                                    "arguments",
                                                    arguments)));
                        chunk =
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
                                                                        "tool_calls",
                                                                        calls),
                                                                "finish_reason",
                                                                "tool_calls"))));
                    } else if (documentMode
                            && !request.getBody().contains("PREPARED")
                            && !request.getBody().contains("skip-document")) {
                        assertThat(request.getBody()).contains("不要执行");
                        var arguments =
                                json.writeValueAsString(
                                        Map.of(
                                                "title",
                                                "资料整理",
                                                "markdown",
                                                "# 资料整理\n\n原始资料包含标题与一段文字。\n\n<script>不要执行</script>\n"));
                        var calls = new ArrayList<Map<String, Object>>();
                        calls.add(
                                Map.of(
                                        "index",
                                        0,
                                        "id",
                                        "write-one",
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "prepare_document",
                                                "arguments",
                                                arguments)));
                        if (request.getBody().contains("duplicate-document"))
                            calls.add(
                                    Map.of(
                                            "index",
                                            1,
                                            "id",
                                            "write-two",
                                            "type",
                                            "function",
                                            "function",
                                            Map.of(
                                                    "name",
                                                    "prepare_document",
                                                    "arguments",
                                                    arguments)));
                        chunk =
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
                                                                        "tool_calls",
                                                                        calls),
                                                                "finish_reason",
                                                                "tool_calls"))));
                    } else
                        chunk =
                                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"资料处理已结束，请查看处理结果。\"},\"finish_reason\":\"stop\"}]}";
                    if (request.getBody().contains("pause-document") && toolResult) {
                        if (documentMode && request.getBody().contains("PREPARED")) {
                            return reactor.core.publisher.Flux.just(answerChunk("资料已整理。", null))
                                    .doOnNext(ignored -> pauseReceived.countDown())
                                    .concatWith(
                                            pauseGate
                                                    .asMono()
                                                    .thenMany(
                                                            reactor.core.publisher.Flux.just(
                                                                    answerChunk("旧回复。", "stop"),
                                                                    "[DONE]")));
                        }
                        if (!documentMode) {
                            assertThat(request.getBody()).contains("资料已整理。", "不要执行");
                            chunk = answerChunk("可以查看成果。", "stop");
                        }
                    }
                    var answer =
                            reactor.core.publisher.Flux.just(
                                    chunk,
                                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
                    if (documentMode && request.getBody().contains("PREPARED")) {
                        if (request.getBody().contains("hold-document")) {
                            holdReceived.countDown();
                            return answer.concatWith(reactor.core.publisher.Flux.never());
                        }
                        if (request.getBody().contains("file-failure")) {
                            fileReceived.countDown();
                            return answer.concatWith(
                                    fileGate.asMono()
                                            .thenMany(reactor.core.publisher.Flux.just("[DONE]")));
                        }
                    }
                    return answer.concatWithValues("[DONE]");
                }

                private String answerChunk(String text, String finish) {
                    var choice =
                            json.createObjectNode().put("index", 0).put("finish_reason", finish);
                    choice.putObject("delta").put("role", "assistant").put("content", text);
                    var event = json.createObjectNode();
                    event.putArray("choices").add(choice);
                    return event.toString();
                }

                public void close() {}
            };
        }
    }

    String task(Browser browser, String model, String material, String message) throws Exception {
        return task(browser, model, List.of(material), message);
    }

    String task(Browser browser, String model, List<String> materials, String message)
            throws Exception {
        String cid =
                json.readTree(browser.send("POST", "/api/conversations", "{}").body())
                        .get("id")
                        .asString();
        var response =
                browser.send(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "modelId",
                                        model,
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "message",
                                        message,
                                        "materialIds",
                                        materials)));
        assertThat(response.statusCode()).isEqualTo(202);
        String id = json.readTree(response.body()).get("id").asString();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var state = json.readTree(browser.send("GET", "/api/tasks/" + id, null).body());
            if (!state.get("status").asString().equals("RUNNING")) {
                assertThat(state.get("status").asString()).isEqualTo("COMPLETED");
                return id;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Task did not finish");
    }

    private java.util.stream.Stream<tools.jackson.databind.JsonNode> agUiEvents(
            Browser browser, String task) throws Exception {
        var response =
                browser.client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:"
                                                        + port
                                                        + "/api/tasks/"
                                                        + task
                                                        + "/ag-ui"))
                                .header("Accept", "text/event-stream")
                                .timeout(java.time.Duration.ofSeconds(30))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body()
                .filter(line -> line.startsWith("data:"))
                .map(line -> json.readTree(line.substring(5)));
    }

    String submitDocument(
            Browser browser, String conversation, String model, String material, String message)
            throws Exception {
        var response =
                browser.send(
                        "POST",
                        "/api/conversations/" + conversation + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "modelId",
                                        model,
                                        "message",
                                        message,
                                        "materialIds",
                                        List.of(material),
                                        "outputMode",
                                        "DOCUMENT")));
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).get("id").asString();
    }

    void awaitTask(Browser browser, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var task = json.readTree(browser.send("GET", "/api/tasks/" + id, null).body());
            if (task.get("status").asString().equals(expected)
                    && task.get("executionState").asString().equals("STOPPED")) return;
            Thread.sleep(100);
        }
        throw new AssertionError(browser.send("GET", "/api/tasks/" + id, null).body());
    }

    void awaitPreparedModelCall(
            Browser browser, String id, java.util.concurrent.CountDownLatch received)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (received.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
            if (!json.readTree(browser.send("GET", "/api/tasks/" + id, null).body())
                    .path("status")
                    .asString()
                    .equals("RUNNING")) break;
        }
        throw new AssertionError(
                "Model did not receive the prepared document result: "
                        + browser.send("GET", "/api/tasks/" + id + "/diagnostics", null).body());
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "Real harness parses authorized material and publishes only task material")
    void realHarnessParsesAuthorizedMaterialAndPublishesOnlyTaskMaterial() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-16");
        admin.send(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-16\",\"newPassword\":\"Changed-password-16\"}");
        admin.login("admin", "Changed-password-16");
        String uid =
                json.readTree(admin.send("GET", "/api/auth/me", null).body()).get("id").asString();
        String model =
                json.readTree(
                                admin.send(
                                                "POST",
                                                "/api/admin/models",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "Parser test",
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
                                                                131072)))
                                        .body())
                        .get("id")
                        .asString();
        admin.send(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(model))));
        admin.send("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"1\"}");
        var material =
                admin.upload(
                        "\ufeff# 资料\r\n<script>不要执行</script>\r原文"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var tid = task(admin, model, material, "请解析资料");
        var parses = json.readTree(admin.send("GET", "/api/tasks/" + tid + "/parses", null).body());
        assertThat(parses.size()).isEqualTo(1);
        assertThat(parses.get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(parses.get(0).get("cleanup").asString()).isEqualTo("CONFIRMED");
        String path = "/api/tasks/" + tid + "/parses/" + parses.get(0).get("id").asString();
        assertThat(json.readTree(admin.send("GET", path, null).body()).get("text").asString())
                .isEqualTo("# 资料\n<script>不要执行</script>\n原文");
        assertThat(admin.send("GET", path + "?offset=999999", null).statusCode()).isEqualTo(400);
        assertThat(admin.send("GET", "/api/materials/" + material + "/content", null).body())
                .startsWith("\ufeff# 资料\r\n");
        String documentConversation =
                json.readTree(admin.send("POST", "/api/conversations", "{}").body())
                        .get("id")
                        .asString();
        String documentRequest =
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                "document-one",
                                "modelId",
                                model,
                                "message",
                                "请把资料整理成正式文档 duplicate-document",
                                "materialIds",
                                List.of(material),
                                "outputMode",
                                "DOCUMENT"));
        holdAdmission.set(true);
        var documentSubmit =
                admin.send(
                        "POST",
                        "/api/conversations/" + documentConversation + "/tasks",
                        documentRequest);
        assertThat(documentSubmit.statusCode()).isEqualTo(202);
        var documentTask = json.readTree(documentSubmit.body()).get("id").asString();
        List<tools.jackson.databind.JsonNode> documentEvents = new ArrayList<>();
        try (var stream = agUiEvents(admin, documentTask)) {
            var events = stream.iterator();
            try {
                while (events.hasNext()) {
                    var event = events.next();
                    documentEvents.add(event);
                    if (event.path("name").asString().equals("iwork.tool")) {
                        assertThat(event.path("value").path("status").asString())
                                .isEqualTo("RUNNING");
                        assertThat(event.path("value").path("materialLabel").asString())
                                .isEqualTo("source.md");
                        assertThat(
                                        json.readTree(
                                                        admin.send(
                                                                        "GET",
                                                                        "/api/tasks/"
                                                                                + documentTask,
                                                                        null)
                                                                .body())
                                                .path("status")
                                                .asString())
                                .isEqualTo("RUNNING");
                        break;
                    }
                }
            } finally {
                admitParse.release();
            }
            events.forEachRemaining(documentEvents::add);
        }
        assertThat(
                        json.readTree(admin.send("GET", "/api/tasks/" + documentTask, null).body())
                                .get("status")
                                .asString())
                .isEqualTo("COMPLETED");
        var documents =
                json.readTree(
                                admin.send(
                                                "GET",
                                                "/api/conversations/"
                                                        + documentConversation
                                                        + "/documents",
                                                null)
                                        .body())
                        .path("items");
        assertThat(documents.size()).isEqualTo(1);
        String version = documents.get(0).get("versionId").asString();
        assertToolEvents(documentEvents, "read_task_material", "source.md", "RUNNING", "COMPLETED");
        assertThat(documentEvents.getLast().path("type").asString()).isEqualTo("RUN_FINISHED");
        assertThat(documentEvents.getLast().path("result").path("versionId").asString())
                .isEqualTo(version);
        assertThat(version)
                .isNotEqualTo(documentTask)
                .isNotEqualTo(documents.get(0).get("id").asString());
        var download = admin.send("GET", "/api/document-versions/" + version + "/content", null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body())
                .isEqualTo("# 资料整理\n\n原始资料包含标题与一段文字。\n\n<script>不要执行</script>\n");
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment");
        assertThat(
                        admin.send(
                                        "POST",
                                        "/api/conversations/" + documentConversation + "/tasks",
                                        documentRequest)
                                .statusCode())
                .isEqualTo(202);
        assertThat(
                        json.readTree(
                                        admin.send(
                                                        "GET",
                                                        "/api/conversations/"
                                                                + documentConversation
                                                                + "/documents",
                                                        null)
                                                .body())
                                .path("items")
                                .size())
                .isEqualTo(1);
        assertThat(
                        admin.send(
                                        "POST",
                                        "/api/conversations/" + documentConversation + "/tasks",
                                        documentRequest.replace("DOCUMENT", "TEXT"))
                                .statusCode())
                .isEqualTo(409);
        assertThat(admin.send("POST", "/api/tasks/" + documentTask + "/cancel", "{}").body())
                .contains("COMPLETED");
        assertThat(admin.send("GET", "/api/document-versions/" + version + "/content", null).body())
                .isEqualTo(download.body());
        var savedFile = java.nio.file.Path.of("target/parsing-files/documents", version + ".md");
        var savedBytes = java.nio.file.Files.readAllBytes(savedFile);
        try {
            java.nio.file.Files.writeString(savedFile, "corrupted fixture");
            assertThat(
                            admin.send(
                                            "GET",
                                            "/api/document-versions/" + version + "/content",
                                            null)
                                    .statusCode())
                    .isEqualTo(503);
        } finally {
            java.nio.file.Files.write(savedFile, savedBytes);
        }
        String failureConversation =
                json.readTree(admin.send("POST", "/api/conversations", "{}").body())
                        .get("id")
                        .asString();
        String missing =
                submitDocument(admin, failureConversation, model, material, "skip-document");
        awaitTask(admin, missing, "FAILED");
        assertThat(admin.send("GET", "/api/tasks/" + missing, null).body())
                .contains("document_not_generated");
        String cancelled =
                submitDocument(admin, failureConversation, model, material, "hold-document");
        awaitPreparedModelCall(admin, cancelled, holdReceived);
        assertThat(admin.send("POST", "/api/tasks/" + cancelled + "/cancel", "{}").statusCode())
                .isEqualTo(200);
        awaitTask(admin, cancelled, "CANCELLED");
        assertThat(
                        json.readTree(
                                        admin.send(
                                                        "GET",
                                                        "/api/conversations/"
                                                                + failureConversation
                                                                + "/documents",
                                                        null)
                                                .body())
                                .path("items")
                                .isEmpty())
                .isTrue();
        String continuationConversation =
                json.readTree(admin.send("POST", "/api/conversations", "{}").body())
                        .get("id")
                        .asString();
        String original =
                submitDocument(admin, continuationConversation, model, material, "pause-document");
        awaitPreparedModelCall(admin, original, pauseReceived);
        var parseBeforePause =
                json.readTree(admin.send("GET", "/api/tasks/" + original + "/parses", null).body());
        assertThat(parseBeforePause.get(0).path("status").asString()).isEqualTo("COMPLETED");
        try (var events =
                admin.client
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://localhost:"
                                                                + port
                                                                + "/api/tasks/"
                                                                + original
                                                                + "/reply-stream"))
                                        .timeout(java.time.Duration.ofSeconds(10))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofLines())
                        .body()) {
            assertThat(events.anyMatch(line -> line.contains("资料已整理。"))).isTrue();
        }
        String pausePath = "/api/tasks/" + original + "/pause";
        assertThat(admin.send("POST", pausePath, null).statusCode()).isEqualTo(200);
        awaitTask(admin, original, "PAUSED");
        int parsesBeforeResume = parseRequests.get();
        String resumePath = "/api/tasks/" + original + "/continue";
        var resumed = admin.send("POST", resumePath, null);
        assertThat(resumed.statusCode()).isEqualTo(202);
        String resumedId = json.readTree(resumed.body()).path("id").asString();
        assertThat(resumedId).isNotEqualTo(original);
        assertThat(json.readTree(admin.send("POST", resumePath, null).body()).path("id").asString())
                .isEqualTo(resumedId);
        pauseGate.tryEmitEmpty();
        awaitTask(admin, resumedId, "COMPLETED");
        assertThat(parseRequests.get()).isEqualTo(parsesBeforeResume);
        var continued =
                json.readTree(
                                admin.send(
                                                "GET",
                                                "/api/conversations/"
                                                        + continuationConversation
                                                        + "/messages",
                                                null)
                                        .body())
                        .path("items");
        assertThat(continued).hasSize(2);
        for (var message : continued)
            assertThat(message.path("taskId").asString()).isEqualTo(original);
        assertThat(continued.get(1).path("content").asString()).isEqualTo("资料已整理。可以查看成果。");
        assertThat(
                        json.readTree(
                                        admin.send(
                                                        "GET",
                                                        "/api/tasks/" + resumedId + "/parses",
                                                        null)
                                                .body())
                                .get(0)
                                .path("id")
                                .asString())
                .isEqualTo(parseBeforePause.get(0).path("id").asString());
        assertThat(
                        json.readTree(
                                        admin.send(
                                                        "GET",
                                                        "/api/conversations/"
                                                                + continuationConversation
                                                                + "/documents",
                                                        null)
                                                .body())
                                .path("items"))
                .hasSize(1);

        String storage =
                submitDocument(admin, failureConversation, model, material, "file-failure");
        awaitPreparedModelCall(admin, storage, fileReceived);
        var directory = java.nio.file.Path.of("target/parsing-files/documents");
        var held = directory.resolveSibling("documents-held-" + UUID.randomUUID());
        java.nio.file.Files.move(directory, held);
        try {
            java.nio.file.Files.writeString(directory, "unavailable directory fixture");
            fileGate.tryEmitEmpty();
            awaitTask(admin, storage, "FAILED");
            assertThat(admin.send("GET", "/api/tasks/" + storage, null).body())
                    .contains("document_storage_unavailable");
            try (var stream = agUiEvents(admin, storage)) {
                var events = stream.toList();
                assertToolEvents(events, "prepare_document", null, "RUNNING", "COMPLETED");
                assertThat(events.getLast().path("type").asString()).isEqualTo("RUN_ERROR");
                assertThat(events.getLast().path("code").asString())
                        .isEqualTo("document_storage_unavailable");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(directory);
            java.nio.file.Files.move(held, directory);
        }
        assertThat(admin.send("GET", "/api/document-versions/" + version + "/content", null).body())
                .isEqualTo(download.body());
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE document_versions RENAME TO document_versions_unavailable");
            try {
                String rejected =
                        submitDocument(admin, failureConversation, model, material, "数据库提交失败验收");
                awaitTask(admin, rejected, "FAILED");
            } finally {
                statement.execute(
                        "ALTER TABLE document_versions_unavailable RENAME TO document_versions");
            }
            assertThat(
                            json.readTree(
                                            admin.send(
                                                            "GET",
                                                            "/api/conversations/"
                                                                    + failureConversation
                                                                    + "/documents",
                                                            null)
                                                    .body())
                                    .path("items")
                                    .isEmpty())
                    .isTrue();
        }
        assertThat(admin.send("GET", "/api/document-versions/" + version + "/content", null).body())
                .isEqualTo(download.body());
        var longMaterial =
                admin.upload(
                        ("a".repeat(11999) + "😀尾")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var longTask = task(admin, model, longMaterial, "请解析较长资料");
        var longParse =
                json.readTree(admin.send("GET", "/api/tasks/" + longTask + "/parses", null).body())
                        .get(0);
        String longPath = "/api/tasks/" + longTask + "/parses/" + longParse.get("id").asString();
        var first = json.readTree(admin.send("GET", longPath, null).body());
        assertThat(first.get("text").asString()).hasSize(11999);
        assertThat(first.get("nextOffset").asInt()).isEqualTo(11999);
        assertThat(
                        json.readTree(admin.send("GET", longPath + "?offset=11999", null).body())
                                .get("text")
                                .asString())
                .isEqualTo("😀尾");
        assertThat(admin.send("GET", longPath + "?offset=12000", null).statusCode()).isEqualTo(400);
        var duplicateTask = task(admin, model, material, "duplicate-material");
        var duplicate =
                json.readTree(
                        admin.send("GET", "/api/tasks/" + duplicateTask + "/parses", null).body());
        assertThat(duplicate.size()).isEqualTo(1);
        assertThat(duplicate.get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(duplicate.get(0).get("characters").isNull()).isFalse();
        var invalid = admin.upload(new byte[] {(byte) 0xff, (byte) 0xfe, 1});
        var failed = task(admin, model, List.of(invalid, material), "请解析资料");
        try (var stream = agUiEvents(admin, failed)) {
            var events = stream.toList();
            assertToolEvents(
                    events, "read_task_material", "source.md（第 1 份资料）", "RUNNING", "FAILED");
            assertThat(events.getLast().path("type").asString()).isEqualTo("RUN_FINISHED");
            assertThat(events.getLast().hasNonNull("result")).isFalse();
        }
        var failedParse =
                json.readTree(admin.send("GET", "/api/tasks/" + failed + "/parses", null).body())
                        .get(0);
        var diagnosis = admin.send("GET", "/api/tasks/" + failed + "/diagnostics", null);
        assertThat(failedParse.get("status").asString()).as(diagnosis.body()).isEqualTo("FAILED");
        assertThat(failedParse.get("cleanup").asString()).isEqualTo("CONFIRMED");
        assertThat(diagnosis.statusCode()).isEqualTo(200);
        assertThat(diagnosis.body())
                .contains(
                        failedParse.path("id").asString(),
                        "material_parse_failed",
                        "CONFIRMED",
                        "cleanupAttemptedAt")
                .doesNotContain("parsed_text", "task_token");
        assertThat(
                        json.readTree(diagnosis.body())
                                .path("parses")
                                .get(0)
                                .path("cleanupAttempts")
                                .asInt())
                .isPositive();
        rejectAdmission.set(true);
        var unavailable = task(admin, model, material, "沙箱暂时不可用");
        var unavailableParse =
                json.readTree(
                                admin.send("GET", "/api/tasks/" + unavailable + "/parses", null)
                                        .body())
                        .get(0);
        assertThat(rejectAdmission.get()).isFalse();
        assertThat(unavailableParse.path("status").asString()).isEqualTo("FAILED");
        assertThat(unavailableParse.path("errorCode").asString()).isEqualTo("sandbox_unavailable");
        assertThat(unavailableParse.path("cleanup").asString()).isEqualTo("CONFIRMED");
        rejectAdmission.set(true);
        rejectCleanup.set(true);
        var retry = task(admin, model, material, "沙箱清理重试");
        var retryParse = json.readTree("{}");
        for (int attempt = 0; attempt < 100; attempt++) {
            retryParse =
                    json.readTree(admin.send("GET", "/api/tasks/" + retry + "/parses", null).body())
                            .get(0);
            if (retryParse.path("cleanup").asString().equals("CONFIRMED")) break;
            Thread.sleep(200);
        }
        assertThat(rejectAdmission.get()).isFalse();
        assertThat(rejectCleanup.get()).isFalse();
        assertThat(retryParse.path("status").asString()).isEqualTo("FAILED");
        assertThat(retryParse.path("errorCode").asString()).isEqualTo("sandbox_unavailable");
        assertThat(retryParse.path("cleanup").asString()).isEqualTo("CONFIRMED");
        var foreign = task(admin, model, material, "foreign-reference");
        assertThat(
                        json.readTree(
                                        admin.send("GET", "/api/tasks/" + foreign + "/parses", null)
                                                .body())
                                .isEmpty())
                .isTrue();
        var created =
                json.readTree(
                        admin.send(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"other-parser\",\"displayName\":\"Other\"}")
                                .body());
        var other = new Browser();
        other.login("other-parser", created.get("temporaryPassword").asString());
        other.send(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                created.get("temporaryPassword").asString(),
                                "newPassword",
                                "Other-password-16")));
        other.login("other-parser", "Other-password-16");
        assertThat(other.send("GET", path, null).statusCode()).isEqualTo(404);
        assertThat(
                        other.send("GET", "/api/document-versions/" + version + "/content", null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        other.send(
                                        "GET",
                                        "/api/conversations/" + documentConversation + "/documents",
                                        null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(other.send("GET", "/api/tasks/" + tid + "/parses", null).statusCode())
                .isEqualTo(404);
    }

    private void assertToolEvents(
            List<tools.jackson.databind.JsonNode> events,
            String name,
            String label,
            String... statuses) {
        var calls =
                events.stream()
                        .filter(event -> event.path("name").asString().equals("iwork.tool"))
                        .map(event -> event.path("value"))
                        .filter(value -> value.path("name").asString().equals(name))
                        .toList();
        assertThat(calls)
                .extracting(call -> call.path("status").asString())
                .containsExactly(statuses);
        assertThat(calls.getFirst().path("id").asString())
                .isNotBlank()
                .isEqualTo(calls.getLast().path("id").asString());
        if (label != null)
            assertThat(calls)
                    .extracting(call -> call.path("materialLabel").asString())
                    .containsOnly(label);
    }
}
