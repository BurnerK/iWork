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
@org.springframework.context.annotation.Import(PersonalAgentTest.ProviderConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/project-space-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "IWORK_MODEL_URL_TEST=https://models.example.test/v1",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
public class ProjectSpaceTest {
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
        r.add(
                "iwork.sandbox-url",
                () -> System.getenv().getOrDefault("IWORK_SANDBOX_TEST_URL", ""));
        r.add(
                "iwork.sandbox-key-file",
                () -> System.getenv().getOrDefault("IWORK_SANDBOX_TEST_KEY_FILE", ""));
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
    @org.junit.jupiter.api.DisplayName(
            "Project references organize owned work without taking ownership")
    void projectReferencesOrganizeOwnedWorkWithoutTakingOwnership() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                "Initial-password-11",
                                "newPassword",
                                "Project-password-23")),
                true);
        admin.login("admin", "Project-password-23");
        admin.csrf();
        var created =
                admin.request(
                        "POST",
                        "/api/projects",
                        json.writeValueAsString(Map.of("name", "蓝鲸项目", "instructions", "按分工组织正文。")),
                        true);
        assertThat(created.statusCode()).isEqualTo(201);
        String project = json.readTree(created.body()).path("id").asString();
        assertThat(admin.request("GET", "/api/projects", null, false).body()).contains(project);
        assertThat(admin.request("GET", "/api/projects/" + project, null, false).body())
                .contains("按分工组织正文。");
        String cid =
                json.readTree(
                                admin.request(
                                                "POST",
                                                "/api/conversations",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "项目会话",
                                                                "projectId",
                                                                project)),
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        assertThat(admin.request("GET", "/api/conversations/" + cid, null, false).body())
                .contains(project);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/projects/" + project + "/conversations",
                                        null,
                                        false)
                                .body())
                .contains(cid);
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
                                                                16384)),
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
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/budget",
                json.writeValueAsString(Map.of("limit", "5")),
                true);
        var upload =
                admin.client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/materials"))
                                .header("X-CSRF-TOKEN", admin.csrf)
                                .header("X-File-Name", "project.md")
                                .header("Content-Type", "application/octet-stream")
                                .POST(HttpRequest.BodyPublishers.ofString("蓝鲸项目，周五交付。"))
                                .build(),
                        io.iwork.ApiTestResponse.body());
        String material = json.readTree(upload.body()).path("id").asString();
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/projects/" + project + "/materials",
                                        json.writeValueAsString(Map.of("materialId", material)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request("GET", "/api/projects/" + project + "/materials", null, false)
                                .body())
                .contains(material);
        String task = submit(admin, cid, model, material);
        await(admin, task);
        var initial =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/projects/" + project + "/documents",
                                                null,
                                                false)
                                        .body())
                        .path("items")
                        .get(0);
        String version = initial.path("versionId").asString(),
                artifact = initial.path("id").asString();
        String content =
                admin.request("GET", "/api/document-versions/" + version + "/content", null, false)
                        .body();
        assertThat(content).contains("分工组织", "蓝鲸项目");
        String second =
                json.readTree(
                                admin.request(
                                                "POST",
                                                "/api/projects",
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "独立引用",
                                                                "instructions",
                                                                "按行动清单组织正文。")),
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/projects/" + second + "/documents",
                                        json.writeValueAsString(Map.of("versionId", version)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/projects/" + second + "/materials",
                                        json.writeValueAsString(Map.of("materialId", material)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request("GET", "/api/projects/" + second + "/documents", null, false)
                                .body())
                .contains(artifact, version);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/conversations/" + cid + "/project",
                                        json.writeValueAsString(Map.of("projectId", second)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        var revision =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "modelId",
                                        model,
                                        "message",
                                        "继续修改蓝鲸项目方案",
                                        "baseVersionId",
                                        version,
                                        "outputMode",
                                        "DOCUMENT")),
                        true);
        assertThat(revision.statusCode()).isEqualTo(202);
        await(admin, json.readTree(revision.body()).path("id").asString());
        var revisions =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/projects/" + second + "/documents",
                                                null,
                                                false)
                                        .body())
                        .path("items");
        assertThat(revisions.get(0).path("id").asString()).isEqualTo(artifact);
        assertThat(revisions.get(0).path("versionNumber").asInt()).isEqualTo(2);
        assertThat(revisions.get(0).path("baseVersionId").asString()).isEqualTo(version);
        assertThat(
                        admin.request("GET", "/api/projects/" + project + "/documents", null, false)
                                .body())
                .contains(revisions.get(0).path("versionId").asString());
        String moved = submit(admin, cid, model, material);
        await(admin, moved);
        var afterMove =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/documents",
                                                null,
                                                false)
                                        .body())
                        .path("items")
                        .get(0);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/"
                                                + afterMove.path("versionId").asString()
                                                + "/content",
                                        null,
                                        false)
                                .body())
                .contains("行动清单")
                .doesNotContain("分工组织");
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/conversations/" + cid + "/project",
                                        "{\"projectId\":null}",
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "GET",
                                                        "/api/conversations/" + cid,
                                                        null,
                                                        false)
                                                .body())
                                .path("projectId")
                                .isNull())
                .isTrue();
        admin.request(
                "PUT",
                "/api/conversations/" + cid + "/project",
                json.writeValueAsString(Map.of("projectId", project)),
                true);
        assertThat(admin.request("DELETE", "/api/projects/" + project, null, true).statusCode())
                .isEqualTo(204);
        var retained =
                json.readTree(
                        admin.request("GET", "/api/conversations/" + cid, null, false).body());
        assertThat(retained.path("id").asString()).isEqualTo(cid);
        assertThat(retained.path("projectId").isNull()).isTrue();
        assertThat(admin.request("GET", "/api/projects/" + project, null, false).statusCode())
                .isEqualTo(404);
        assertThat(
                        admin.request("GET", "/api/materials/" + material + "/content", null, false)
                                .body())
                .contains("蓝鲸项目");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + version + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(content);
        assertThat(
                        admin.request("GET", "/api/projects/" + second + "/documents", null, false)
                                .body())
                .contains(artifact, version);
        assertThat(admin.request("GET", "/api/document-versions", null, false).body())
                .contains(version);
        var account =
                json.readTree(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"project-other\",\"displayName\":\"Other\"}",
                                        true)
                                .body());
        var other = new Browser();
        other.login("project-other", account.path("temporaryPassword").asString());
        other.csrf();
        other.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                account.path("temporaryPassword").asString(),
                                "newPassword",
                                "Other-project-password-23")),
                true);
        other.login("project-other", "Other-project-password-23");
        other.csrf();
        String otherProject =
                json.readTree(
                                other.request(
                                                "POST",
                                                "/api/projects",
                                                "{\"name\":\"Private\"}",
                                                true)
                                        .body())
                        .path("id")
                        .asString();
        assertThat(other.request("GET", "/api/projects", null, false).body())
                .doesNotContain(second);
        for (String suffix : List.of("", "/materials", "/documents", "/conversations")) {
            assertThat(
                            other.request("GET", "/api/projects/" + second + suffix, null, false)
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            admin.request(
                                            "GET",
                                            "/api/projects/" + otherProject + suffix,
                                            null,
                                            false)
                                    .statusCode())
                    .isEqualTo(404);
        }
        assertThat(
                        other.request(
                                        "POST",
                                        "/api/projects/" + otherProject + "/materials",
                                        json.writeValueAsString(Map.of("materialId", material)),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        other.request(
                                        "POST",
                                        "/api/projects/" + otherProject + "/documents",
                                        json.writeValueAsString(Map.of("versionId", version)),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        other.request(
                                        "PUT",
                                        "/api/conversations/" + cid + "/project",
                                        json.writeValueAsString(Map.of("projectId", otherProject)),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        admin.request(
                                        "PUT",
                                        "/api/conversations/" + cid + "/project",
                                        json.writeValueAsString(Map.of("projectId", otherProject)),
                                        true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        admin.request("DELETE", "/api/projects/" + otherProject, null, true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/projects/" + second + "/materials",
                                        json.writeValueAsString(Map.of("materialId", material)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request(
                                        "DELETE",
                                        "/api/projects/" + second + "/materials/" + material,
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request("GET", "/api/materials/" + material + "/content", null, false)
                                .body())
                .contains("蓝鲸项目");
        assertThat(
                        admin.request(
                                        "DELETE",
                                        "/api/projects/" + second + "/documents/" + artifact,
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/" + version + "/content",
                                        null,
                                        false)
                                .body())
                .isEqualTo(content);
        String independent = submit(admin, cid, model, material);
        await(admin, independent);
        var afterDelete =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/documents",
                                                null,
                                                false)
                                        .body())
                        .path("items")
                        .get(0);
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/document-versions/"
                                                + afterDelete.path("versionId").asString()
                                                + "/content",
                                        null,
                                        false)
                                .body())
                .contains("行动清单")
                .doesNotContain("分工组织");
    }

    String submit(Browser browser, String cid, String model, String material) throws Exception {
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
                                        "整理项目方案",
                                        "materialIds",
                                        List.of(material),
                                        "outputMode",
                                        "DOCUMENT")),
                        true);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("id").asString();
    }

    void await(Browser browser, String task) throws Exception {
        for (int i = 0; i < 300; i++) {
            var state =
                    json.readTree(browser.request("GET", "/api/tasks/" + task, null, false).body());
            if (!state.path("status").asString().equals("RUNNING")) {
                assertThat(state.path("status").asString())
                        .as(state.toString())
                        .isEqualTo("COMPLETED");
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not finish");
    }
}
