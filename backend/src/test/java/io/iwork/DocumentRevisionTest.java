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
            "iwork.files-directory=target/document-revision-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class DocumentRevisionTest {
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

    static volatile java.util.concurrent.CountDownLatch preparedGate =
            new java.util.concurrent.CountDownLatch(0);
    static volatile reactor.core.publisher.Sinks.Empty<Void> finishGate =
            reactor.core.publisher.Sinks.empty();

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
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
                                source && !read ? "read_document_version" : "prepare_document";
                        var args =
                                tool.equals("read_document_version")
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
                    var output =
                            reactor.core.publisher.Flux.just(
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
                                                                    finish)))));
                    String current = "";
                    for (var message : body.path("messages"))
                        if (message.path("role").asString().equals("user"))
                            current = message.path("content").asString();
                    if (prepared && current.contains("gated")) {
                        preparedGate.countDown();
                        return output.concatWith(
                                finishGate
                                        .asMono()
                                        .thenMany(reactor.core.publisher.Flux.just("[DONE]")));
                    }
                    return output.concatWithValues("[DONE]");
                }

                public void close() {}
            };
        }
    }

    @Test
    void savedVersionsRemainImmutableAndCanBeReusedAcrossConversations() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Revision-password-18\"}",
                true);
        admin.login("admin", "Revision-password-18");
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
        String first = conversation(admin);
        await(admin, start(admin, first, model, null));
        var version =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + first + "/documents",
                                                null,
                                                false)
                                        .body())
                        .get(0);
        String id = version.path("versionId").asString(), artifact = version.path("id").asString();
        var original =
                admin.request("GET", "/api/document-versions/" + id + "/content", null, false)
                        .body();
        String other = conversation(admin);
        String revised = start(admin, other, model, id);
        await(admin, revised);
        var versions =
                json.readTree(
                        admin.request(
                                        "GET",
                                        "/api/conversations/" + other + "/documents",
                                        null,
                                        false)
                                .body());
        assertThat(versions.size()).isEqualTo(2);
        var latest = versions.get(0);
        assertThat(latest.path("id").asString()).isEqualTo(artifact);
        assertThat(latest.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(latest.path("baseVersionId").asString()).isEqualTo(id);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/"
                                                + latest.path("versionId").asString()
                                                + "/content",
                                        null,
                                        false)
                                .body())
                .contains("补充执行步骤");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + id + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(original);
        var personal =
                json.readTree(
                        admin.request("GET", "/api/document-versions?page=0", null, false).body());
        assertThat(personal.path("items").size()).isEqualTo(2);
        preparedGate = new java.util.concurrent.CountDownLatch(2);
        finishGate = reactor.core.publisher.Sinks.empty();
        String left = start(admin, conversation(admin), model, id, "gated left"),
                right = start(admin, conversation(admin), model, id, "gated right");
        assertThat(preparedGate.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        finishGate.tryEmitEmpty();
        await(admin, left);
        await(admin, right);
        assertThat(
                        db.queryForList(
                                "SELECT version_number FROM document_versions WHERE document_id=? ORDER BY version_number",
                                Integer.class,
                                artifact))
                .containsExactly(1, 2, 3, 4);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM document_versions WHERE document_id=? AND base_version_id=?",
                                Integer.class,
                                artifact,
                                id))
                .isEqualTo(3);
        var retryInput =
                Map.of(
                        "requestId",
                        "repeat-version",
                        "modelId",
                        model,
                        "message",
                        "再次基于旧版修改",
                        "outputMode",
                        "DOCUMENT",
                        "baseVersionId",
                        id);
        String retried =
                json.readTree(
                                admin.request(
                                                "POST",
                                                "/api/conversations/" + other + "/tasks",
                                                json.writeValueAsString(retryInput),
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        await(admin, retried);
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "POST",
                                                        "/api/conversations/" + other + "/tasks",
                                                        json.writeValueAsString(retryInput),
                                                        true)
                                                .body())
                                .path("id")
                                .asString())
                .isEqualTo(retried);
        var conflict = new HashMap<String, Object>(retryInput);
        conflict.put("baseVersionId", latest.path("versionId").asString());
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/conversations/" + other + "/tasks",
                                        json.writeValueAsString(conflict),
                                        true)
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM document_versions WHERE task_id=?",
                                Integer.class,
                                retried))
                .isEqualTo(1);
        preparedGate = new java.util.concurrent.CountDownLatch(1);
        finishGate = reactor.core.publisher.Sinks.empty();
        String broken = start(admin, conversation(admin), model, id, "gated save failure");
        assertThat(preparedGate.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        db.execute("ALTER TABLE document_versions RENAME TO document_versions_unavailable");
        try {
            finishGate.tryEmitEmpty();
            await(admin, broken, "FAILED");
        } finally {
            db.execute("ALTER TABLE document_versions_unavailable RENAME TO document_versions");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM document_versions WHERE task_id=?",
                                Integer.class,
                                broken))
                .isZero();
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + id + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(original);
        assertThat(
                        java.nio.file.Files.exists(
                                java.nio.file.Path.of(
                                        "target/document-revision-files/document-workspaces",
                                        revised)))
                .isFalse();
        admin.request(
                "PUT",
                "/api/admin/models/" + model,
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
                                16384)),
                true);
        String background =
                json.readTree(
                                admin.request(
                                                "POST",
                                                "/api/conversations/" + first + "/tasks",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "requestId",
                                                                "long-background",
                                                                "modelId",
                                                                model,
                                                                "message",
                                                                "常规背景".repeat(500))),
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        await(admin, background);
        String compacted = start(admin, first, model, id);
        await(admin, compacted);
        var calls =
                json.readTree(
                        admin.request("GET", "/api/tasks/" + compacted + "/calls", null, false)
                                .body());
        assertThat(calls.size()).isEqualTo(4);
        assertThat(calls.toString()).contains("SUMMARY");
        assertThat(
                        db.queryForObject(
                                "SELECT base_version_id FROM document_versions WHERE task_id=?",
                                String.class,
                                compacted))
                .isEqualTo(id);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + id + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(original);
        var created =
                json.readTree(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"revision-user\",\"displayName\":\"Revision user\"}",
                                        true)
                                .body());
        String password = created.path("temporaryPassword").asString();
        var ordinary = new Browser();
        ordinary.login("revision-user", password);
        ordinary.csrf();
        ordinary.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                password,
                                "newPassword",
                                "Revision-user-password-18")),
                true);
        ordinary.login("revision-user", "Revision-user-password-18");
        ordinary.csrf();
        String own = conversation(ordinary);
        var foreign = new HashMap<String, Object>(retryInput);
        foreign.put("requestId", "foreign-version");
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/conversations/" + own + "/tasks",
                                        json.writeValueAsString(foreign),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request("GET", "/api/document-versions/" + id, null, false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        json.readTree(
                                        ordinary.request(
                                                        "GET",
                                                        "/api/document-versions",
                                                        null,
                                                        false)
                                                .body())
                                .path("items")
                                .isEmpty())
                .isTrue();
    }

    String conversation(Browser browser) throws Exception {
        return json.readTree(browser.request("POST", "/api/conversations", "{}", true).body())
                .path("id")
                .asString();
    }

    String start(Browser browser, String cid, String model, String base) throws Exception {
        return start(browser, cid, model, base, "请生成或补充项目计划");
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
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(202);
        return json.readTree(response.body()).path("id").asString();
    }

    void await(Browser browser, String id) throws Exception {
        await(browser, id, "COMPLETED");
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
