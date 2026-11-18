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
            "iwork.files-directory=target/model-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class ModelConfigurationTest {
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

    @Test
    @org.junit.jupiter.api.DisplayName("Administrator saves model without returning credentials")
    void administratorSavesModelWithoutReturningCredentials() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/auth/password",
                                        "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Changed-password-12\"}",
                                        true)
                                .statusCode())
                .isEqualTo(204);
        admin.login("admin", "Changed-password-12");
        admin.csrf();
        var input =
                Map.of(
                        "name",
                        "Writing",
                        "baseUrl",
                        "https://models.example.test/v1",
                        "modelName",
                        "writer",
                        "credentialRef",
                        "IWORK_MODEL_KEY_TEST",
                        "inputPrice",
                        "1.250000",
                        "outputPrice",
                        "2.500000",
                        "enabled",
                        true);
        var created =
                admin.request("POST", "/api/admin/models", json.writeValueAsString(input), true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("apiKey", "password");
        var id = json.readTree(created.body()).get("id").asString();
        var listed = admin.request("GET", "/api/admin/models", null, false);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(id, "Writing", "1.250000");
        var createdUser =
                admin.request(
                        "POST",
                        "/api/admin/users",
                        "{\"username\":\"model-user\",\"displayName\":\"Model user\"}",
                        true);
        assertThat(createdUser.statusCode()).isEqualTo(201);
        var target = json.readTree(createdUser.body());
        var uid = target.get("user").get("id").asString();
        var ordinary = new Browser();
        ordinary.login("model-user", target.get("temporaryPassword").asString());
        ordinary.csrf();
        ordinary.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                target.get("temporaryPassword").asString(),
                                "newPassword",
                                "Model-user-password")),
                true);
        ordinary.login("model-user", "Model-user-password");
        ordinary.csrf();
        assertThat(ordinary.request("GET", "/api/models", null, false).body()).isEqualTo("[]");
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/users/" + uid + "/model-access",
                                        json.writeValueAsString(Map.of("modelIds", List.of(id))),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        var visible = ordinary.request("GET", "/api/models", null, false);
        assertThat(visible.statusCode()).isEqualTo(200);
        assertThat(visible.body())
                .contains(id, "Writing")
                .doesNotContain("credentialRef", "baseUrl", "IWORK_MODEL_KEY_TEST");
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/users/" + uid + "/budget",
                                        "{\"limit\":\"10.123456\"}",
                                        true)
                                .statusCode())
                .isEqualTo(200);
        var budget = ordinary.request("GET", "/api/budget", null, false);
        assertThat(budget.statusCode()).isEqualTo(200);
        assertThat(budget.body()).contains("10.123456", "CNY");
        assertThat(
                        ordinary.request("GET", "/api/admin/users/" + uid + "/budget", null, false)
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/admin/models",
                                        json.writeValueAsString(input),
                                        true)
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/users/" + uid + "/model-access",
                                        "{\"modelIds\":[]}",
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(ordinary.request("GET", "/api/models", null, false).body()).isEqualTo("[]");

        var changed = new HashMap<String, Object>(input);
        changed.put("enabled", false);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/models/" + id,
                                        json.writeValueAsString(changed),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/model-settings",
                                        "{\"currency\":\"USD\"}",
                                        true)
                                .statusCode())
                .isEqualTo(409);
        for (String amount : List.of("-1", "0.1234567", "1e3", "1000000000000", "NaN")) {
            assertThat(
                            admin.request(
                                            "PUT",
                                            "/api/admin/users/" + uid + "/budget",
                                            json.writeValueAsString(Map.of("limit", amount)),
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
        }
        changed.put("baseUrl", "https://user:secret@models.example.test/v1");
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/models/" + id,
                                        json.writeValueAsString(changed),
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/users/" + uid + "/model-access",
                                        "{\"modelIds\":[\"00000000-0000-0000-0000-000000000000\"]}",
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/admin/users/missing/budget",
                                        "{\"limit\":\"5\"}",
                                        true)
                                .statusCode())
                .isEqualTo(404);

        changed.put("baseUrl", input.get("baseUrl"));
        changed.put("credentialRef", "IWORK_MODEL_KEY_MISSING");
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/admin/models",
                                        json.writeValueAsString(changed),
                                        true)
                                .statusCode())
                .isEqualTo(400);

        var conversation = admin.request("POST", "/api/conversations", "{}", true);
        var conversationId = json.readTree(conversation.body()).get("id").asString();
        var noPermission =
                admin.request(
                        "POST",
                        "/api/conversations/" + conversationId + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        "no-permission",
                                        "modelId",
                                        id,
                                        "message",
                                        "Require explicit model access")),
                        true);
        assertThat(noPermission.statusCode()).isEqualTo(403);
        assertThat(noPermission.body()).contains("model_not_authorized");
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "GET",
                                                        "/api/conversations/"
                                                                + conversationId
                                                                + "/tasks",
                                                        null,
                                                        false)
                                                .body())
                                .get("items")
                                .size())
                .isZero();
    }
}
