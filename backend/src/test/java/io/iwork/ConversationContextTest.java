package io.iwork;

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
            "iwork.files-directory=target/conversation-context-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class ConversationContextTest {
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
                    io.iwork.ApiTestResponse.body());
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

    static volatile String summaryMode = "normal";
    static volatile java.util.concurrent.CountDownLatch summaryStarted =
            new java.util.concurrent.CountDownLatch(0);
    static volatile reactor.core.publisher.Sinks.Empty<Void> responseGate =
            reactor.core.publisher.Sinks.empty();
    static volatile java.util.concurrent.CountDownLatch responseStarted =
            new java.util.concurrent.CountDownLatch(0);

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport providerFixture() {
            return new io.agentscope.core.model.transport.HttpTransport() {
                public io.agentscope.core.model.transport.HttpResponse execute(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    throw new AssertionError("stream only");
                }

                public reactor.core.publisher.Flux<String> stream(
                        io.agentscope.core.model.transport.HttpRequest request) {
                    var messages = new ObjectMapper().readTree(request.getBody()).path("messages");
                    String last = messages.get(messages.size() - 1).path("content").asString();
                    boolean summarizing = last.contains("Context Extraction Assistant");
                    if (summarizing) {
                        summaryStarted.countDown();
                        if (summaryMode.equals("error"))
                            return reactor.core.publisher.Flux.error(
                                    new IllegalStateException("synthetic-summary-failure"));
                        if (summaryMode.equals("empty"))
                            return reactor.core.publisher.Flux.just("[DONE]");
                        if (summaryMode.equals("hold")) return reactor.core.publisher.Flux.never();
                    }
                    String reply =
                            last.contains("Context Extraction Assistant")
                                    ? (last.contains("蓝鲸项目") ? "蓝鲸项目，周五交付" : "没有项目背景")
                                    : last.contains("请回忆")
                                            ? (messages.toString().contains("蓝鲸项目")
                                                    ? "蓝鲸项目，周五交付"
                                                    : "没有历史")
                                            : "已记录";
                    if (summarizing && summaryMode.equals("large")) reply = "概述".repeat(1000);
                    String chunk =
                            new ObjectMapper()
                                    .writeValueAsString(
                                            Map.of(
                                                    "id",
                                                    "context-fixture",
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
                                                                    "stop"))));
                    if (last.contains("等待保存") || summarizing && summaryMode.equals("gate")) {
                        responseStarted.countDown();
                        return reactor.core.publisher.Flux.just(chunk)
                                .concatWith(
                                        responseGate
                                                .asMono()
                                                .thenMany(
                                                        reactor.core.publisher.Flux.just(
                                                                "[DONE]")));
                    }
                    String usage =
                            "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}";
                    return summarizing && !summaryMode.equals("missing")
                            ? reactor.core.publisher.Flux.just(chunk, usage, usage, "[DONE]")
                            : reactor.core.publisher.Flux.just(chunk, "[DONE]");
                }

                public void close() {}
            };
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "Explicit tasks continue only their own completed conversation")
    void explicitTasksContinueOnlyTheirOwnCompletedConversation() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/auth/password",
                                        "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Context-password-27\"}",
                                        true)
                                .statusCode())
                .isEqualTo(204);
        admin.login("admin", "Context-password-27");
        admin.csrf();
        var model =
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
                        true);
        assertThat(model.statusCode()).isEqualTo(201);
        String modelId = json.readTree(model.body()).path("id").asString();
        String uid =
                json.readTree(admin.request("GET", "/api/auth/me", null, false).body())
                        .path("id")
                        .asString();
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(modelId))),
                true);
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"5\"}", true);
        String cid = conversation(admin);
        task(admin, cid, modelId, "蓝鲸项目，周五交付");
        task(admin, cid, modelId, "请回忆项目与交付时间");
        var history =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/messages",
                                                null,
                                                false)
                                        .body())
                        .path("items");
        assertThat(history.get(3).path("content").asString()).isEqualTo("蓝鲸项目，周五交付");
        var limited =
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
                        8192);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/models/" + modelId,
                                        json.writeValueAsString(limited),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        task(admin, cid, modelId, "补充说明" + "常规信息".repeat(150));
        String compacted = task(admin, cid, modelId, "请回忆项目与交付时间");
        assertThat(admin.request("GET", "/api/tasks/" + compacted + "/calls", null, false).body())
                .contains("SUMMARY", "OBSERVED_ESTIMATE");
        var complete =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/messages",
                                                null,
                                                false)
                                        .body())
                        .path("items");
        assertThat(complete.size()).isEqualTo(8);
        assertThat(complete.get(7).path("content").asString()).isEqualTo("蓝鲸项目，周五交付");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/admin/tasks/" + compacted + "/diagnostics",
                                        null,
                                        false)
                                .body())
                .contains("SUMMARY", "COMPACTED")
                .doesNotContain("蓝鲸项目", "周五交付");
        assertThat(
                        db.queryForObject(
                                "SELECT summary FROM conversation_contexts WHERE task_id=?",
                                String.class,
                                compacted))
                .contains("蓝鲸项目");
        for (String mode : List.of("error", "empty", "missing", "large")) {
            String fallback = seed(admin, modelId);
            summaryMode = mode;
            String continued = task(admin, fallback, modelId, "请回忆项目与交付时间");
            var result =
                    json.readTree(
                                    admin.request(
                                                    "GET",
                                                    "/api/conversations/" + fallback + "/messages",
                                                    null,
                                                    false)
                                            .body())
                            .path("items");
            assertThat(result.get(result.size() - 1).path("content").asString())
                    .isEqualTo("蓝鲸项目，周五交付");
            String saved =
                    db.queryForObject(
                            "SELECT context_json FROM conversation_contexts WHERE task_id=?",
                            String.class,
                            continued);
            assertThat(saved)
                    .doesNotContain(
                            "Summarization failed",
                            "Summary unavailable",
                            "synthetic-summary-failure");
            if (mode.equals("missing"))
                assertThat(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + continued + "/calls",
                                                null,
                                                false)
                                        .body())
                        .contains("SUMMARY", "OBSERVED_ESTIMATE");
            String diagnostic =
                    admin.request("GET", "/api/tasks/" + continued + "/diagnostics", null, false)
                            .body();
            assertThat(diagnostic)
                    .contains(mode.equals("missing") ? "COMPACTED" : "FALLBACK")
                    .doesNotContain("synthetic-summary-failure", "蓝鲸项目");
            summaryMode = "normal";
        }
        String over = seed(admin, modelId);
        summaryMode = "error";
        String oversized = start(admin, over, modelId, "x".repeat(4000));
        await(admin, oversized, "FAILED");
        assertThat(admin.request("GET", "/api/tasks/" + oversized, null, false).body())
                .contains("context_compaction_failed");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts WHERE task_id=?",
                                Integer.class,
                                oversized))
                .isZero();
        summaryMode = "normal";
        String cancelledConversation = seed(admin, modelId);
        summaryMode = "hold";
        summaryStarted = new java.util.concurrent.CountDownLatch(1);
        String cancelled = start(admin, cancelledConversation, modelId, "请回忆");
        assertThat(summaryStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(
                        admin.request("POST", "/api/tasks/" + cancelled + "/cancel", "{}", true)
                                .statusCode())
                .isEqualTo(200);
        await(admin, cancelled, "CANCELLED");
        summaryMode = "normal";
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts WHERE task_id=?",
                                Integer.class,
                                cancelled))
                .isZero();
        task(admin, cancelledConversation, modelId, "请回忆");
        db.update(
                "UPDATE conversation_contexts SET context_hash=? WHERE conversation_id=?",
                "0".repeat(64),
                cid);
        String rebuilt = task(admin, cid, modelId, "请回忆项目与交付时间");
        assertThat(
                        admin.request("GET", "/api/tasks/" + rebuilt + "/diagnostics", null, false)
                                .body())
                .contains("SNAPSHOT_REJECTED")
                .doesNotContain("蓝鲸项目");
        String faulty = seed(admin, modelId);
        long beforeFault =
                db.queryForObject(
                        "SELECT COUNT(*) FROM conversation_contexts WHERE conversation_id=?",
                        Long.class,
                        faulty);
        responseGate = reactor.core.publisher.Sinks.empty();
        responseStarted = new java.util.concurrent.CountDownLatch(1);
        String writeFailure = start(admin, faulty, modelId, "等待保存");
        assertThat(responseStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        db.execute("ALTER TABLE conversation_contexts RENAME TO conversation_contexts_unavailable");
        try {
            responseGate.tryEmitEmpty();
            await(admin, writeFailure, "FAILED");
        } finally {
            db.execute(
                    "ALTER TABLE conversation_contexts_unavailable RENAME TO conversation_contexts");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts WHERE conversation_id=?",
                                Long.class,
                                faulty))
                .isEqualTo(beforeFault);
        task(admin, faulty, modelId, "请回忆");
        String revokedConversation = seed(admin, modelId);
        summaryMode = "gate";
        responseGate = reactor.core.publisher.Sinks.empty();
        responseStarted = new java.util.concurrent.CountDownLatch(1);
        String revoked = start(admin, revokedConversation, modelId, "请回忆");
        assertThat(responseStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        admin.request(
                "PUT", "/api/admin/users/" + uid + "/model-access", "{\"modelIds\":[]}", true);
        responseGate.tryEmitEmpty();
        await(admin, revoked, "FAILED");
        summaryMode = "normal";
        assertThat(admin.request("GET", "/api/tasks/" + revoked, null, false).body())
                .contains("model_not_authorized");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts WHERE task_id=?",
                                Integer.class,
                                revoked))
                .isZero();
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(modelId))),
                true);
        String broke = seed(admin, modelId);
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"0\"}", true);
        String exhausted = start(admin, broke, modelId, "请回忆");
        await(admin, exhausted, "FAILED");
        assertThat(admin.request("GET", "/api/tasks/" + exhausted, null, false).body())
                .contains("budget_exhausted");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM conversation_contexts WHERE task_id=?",
                                Integer.class,
                                exhausted))
                .isZero();
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"5\"}", true);
        var created =
                json.readTree(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"context-user\",\"displayName\":\"Context user\"}",
                                        true)
                                .body());
        String password = created.path("temporaryPassword").asString();
        var ordinary = new Browser();
        ordinary.login("context-user", password);
        ordinary.csrf();
        ordinary.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                password,
                                "newPassword",
                                "Context-user-password-27")),
                true);
        ordinary.login("context-user", "Context-user-password-27");
        ordinary.csrf();
        assertThat(
                        ordinary.request(
                                        "GET",
                                        "/api/conversations/" + cid + "/messages",
                                        null,
                                        false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request("GET", "/api/tasks/" + compacted + "/calls", null, false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/conversations/" + cid + "/tasks",
                                        json.writeValueAsString(
                                                Map.of(
                                                        "requestId",
                                                        "foreign",
                                                        "modelId",
                                                        modelId,
                                                        "message",
                                                        "请回忆")),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        String other = conversation(admin);
        task(admin, other, modelId, "请回忆项目与交付时间");
        var isolated =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + other + "/messages",
                                                null,
                                                false)
                                        .body())
                        .path("items");
        assertThat(isolated.get(1).path("content").asString()).isEqualTo("没有历史");
    }

    String conversation(Browser browser) throws Exception {
        return json.readTree(browser.request("POST", "/api/conversations", "{}", true).body())
                .path("id")
                .asString();
    }

    String seed(Browser browser, String model) throws Exception {
        String cid = conversation(browser);
        task(browser, cid, model, "蓝鲸项目，周五交付");
        task(browser, cid, model, "补充说明" + "常规信息".repeat(150));
        return cid;
    }

    String task(Browser browser, String cid, String model, String message) throws Exception {
        String id = start(browser, cid, model, message);
        await(browser, id, "COMPLETED");
        return id;
    }

    String start(Browser browser, String cid, String model, String message) throws Exception {
        var response =
                browser.request(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "modelId",
                                        model,
                                        "message",
                                        message)),
                        true);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("id").asString();
    }

    void await(Browser browser, String id, String expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            var state =
                    json.readTree(browser.request("GET", "/api/tasks/" + id, null, false).body());
            if (state.path("executionState").asString().equals("STOPPED")) {
                assertThat(state.path("status").asString())
                        .describedAs(state.toString())
                        .isEqualTo(expected);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not finish");
    }
}
