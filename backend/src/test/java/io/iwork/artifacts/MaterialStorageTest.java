package io.iwork.artifacts;

import static org.assertj.core.api.Assertions.assertThat;

import io.iwork.ApiTestResponse;
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
            "iwork.cookie-secure=false",
            "iwork.material-owner-max-bytes=2097152",
            "iwork.material-instance-max-bytes=4194304",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class MaterialStorageTest {
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
        r.add("iwork.files-directory", () -> files.toString());
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path files;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    io.iwork.identity.IdentityService identity;

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
                    ApiTestResponse.body());
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

    @Test
    @org.junit.jupiter.api.DisplayName("Owner uploads and retrieves an independent original")
    void ownerUploadsAndRetrievesAnIndependentOriginal() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Material-password-15\"}",
                true);
        admin.login("admin", "Material-password-15");
        admin.csrf();
        var bytes = "项目资料\n保留原文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var started = java.time.Instant.now().minusSeconds(2);
        var upload = upload(admin, "资料.txt", bytes);
        assertThat(upload.statusCode()).isEqualTo(201);
        var id = json.readTree(upload.body()).get("id").asString();
        assertThat(
                        java.time.Instant.parse(
                                json.readTree(upload.body()).get("createdAt").asString()))
                .isBetween(started, java.time.Instant.now().plusSeconds(2));
        var listed = admin.request("GET", "/api/materials", null, false);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(id, "资料.txt").doesNotContain("path");
        var content =
                admin.client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:"
                                                        + port
                                                        + "/api/materials/"
                                                        + id
                                                        + "/content"))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.body()).isEqualTo(bytes);
        assertThat(content.headers().firstValue("Content-Disposition").orElseThrow())
                .startsWith("attachment;");
        assertThat(upload(admin, "../escape.txt", bytes).statusCode()).isEqualTo(400);
        assertThat(upload(admin, "txt", bytes).statusCode()).isEqualTo(415);
        assertThat(upload(admin, "report.pdf", bytes).statusCode()).isEqualTo(415);
        assertThat(upload(admin, "empty.txt", new byte[0]).statusCode()).isEqualTo(400);
        assertThat(upload(admin, "large.txt", new byte[2 * 1024 * 1024 + 1]).statusCode())
                .isEqualTo(413);
        var created =
                json.readTree(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"other\",\"displayName\":\"Other\"}",
                                        true)
                                .body());
        var other = new Browser();
        var password = created.get("temporaryPassword").asString();
        other.login("other", password);
        other.csrf();
        other.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of("currentPassword", password, "newPassword", "Other-password-15")),
                true);
        other.login("other", "Other-password-15");
        other.csrf();
        assertThat(other.request("GET", "/api/materials/" + id, null, false).statusCode())
                .isEqualTo(404);
        assertThat(
                        other.request("GET", "/api/materials/" + id + "/content", null, false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(other.request("GET", "/api/materials", null, false).body()).doesNotContain(id);
        var conversation =
                json.readTree(other.request("POST", "/api/conversations", "{}", true).body())
                        .get("id")
                        .asString();
        var reference =
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                "foreign-material",
                                "modelId",
                                UUID.randomUUID().toString(),
                                "message",
                                "read",
                                "materialIds",
                                List.of(id)));
        assertThat(
                        other.request(
                                        "POST",
                                        "/api/conversations/" + conversation + "/tasks",
                                        reference,
                                        true)
                                .statusCode())
                .isEqualTo(404);

        var again = upload(admin, "资料.txt", bytes);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json.readTree(again.body()).get("id").asString()).isNotEqualTo(id);
        assertThat(
                        json.readTree(admin.request("GET", "/api/materials", null, false).body())
                                .get("items")
                                .size())
                .isEqualTo(2);
        var originals = files.resolve("originals");
        java.nio.file.Files.write(originals.resolve(id), new byte[] {1});
        assertThat(
                        admin.request("GET", "/api/materials/" + id + "/content", null, false)
                                .statusCode())
                .isEqualTo(503);

        var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first =
                    callers.submit(
                            () -> {
                                start.await();
                                return upload(admin, "first.txt", new byte[1536 * 1024]);
                            });
            var second =
                    callers.submit(
                            () -> {
                                start.await();
                                return upload(admin, "second.txt", new byte[1536 * 1024]);
                            });
            start.countDown();
            var responses =
                    List.of(
                            first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses)
                    .extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(responses)
                    .filteredOn(response -> response.statusCode() == 409)
                    .allSatisfy(
                            response ->
                                    assertThat(response.body())
                                            .contains("material_quota_exceeded"));
        } finally {
            callers.shutdownNow();
        }

        var orphan =
                java.nio.file.Files.write(
                        originals.resolve(".orphan.part"), new byte[3 * 1024 * 1024]);
        try {
            var denied = upload(other, "instance.txt", bytes);
            assertThat(denied.statusCode()).isEqualTo(503);
            assertThat(denied.body()).contains("material_storage_unavailable");
            try (var stored = java.nio.file.Files.list(originals)) {
                assertThat(stored.count()).isEqualTo(4);
            }
        } finally {
            java.nio.file.Files.delete(orphan);
        }
        assertThat(upload(other, "independent.txt", bytes).statusCode()).isEqualTo(201);

        var noFreeSpace =
                new io.iwork.artifacts.MaterialService(
                        originals.getParent(),
                        db,
                        tx,
                        identity,
                        2097152,
                        1000,
                        4194304,
                        10000,
                        Long.MAX_VALUE);
        var caller = identity.authenticate("other", "Other-password-15");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                noFreeSpace.upload(
                                        caller,
                                        "reserve.txt",
                                        bytes.length,
                                        new java.io.ByteArrayInputStream(bytes)))
                .isInstanceOf(io.iwork.identity.ApiFailure.class)
                .hasMessage("material_storage_unavailable");
        try (var stored = java.nio.file.Files.list(originals)) {
            assertThat(stored.count()).isEqualTo(4);
        }
    }

    HttpResponse<String> upload(Browser browser, String name, byte[] bytes) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/materials"))
                        .header("X-CSRF-TOKEN", browser.csrf)
                        .header(
                                "X-File-Name",
                                URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
        return browser.client.send(request, ApiTestResponse.body());
    }
}
