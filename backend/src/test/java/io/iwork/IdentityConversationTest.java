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

@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/identity-files", "iwork.cookie-secure=false",
            "iwork.bootstrap.username=admin", "iwork.bootstrap.password=Initial-password-11"
        })
class IdentityConversationTest {
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

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.now();

        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public java.time.Instant instant() {
            return now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired MutableClock clock;
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

    @Test
    @org.junit.jupiter.api.Order(1)
    void bootstrapLoginRequiresPasswordChangeBeforeConversations() throws Exception {
        var browser = new Browser();
        assertThat(browser.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(browser.login("admin", "Initial-password-11").statusCode()).isEqualTo(200);
        assertThat(
                        json.readTree(browser.request("GET", "/api/auth/me", null, false).body())
                                .get("mustChangePassword")
                                .asBoolean())
                .isTrue();
        assertThat(browser.request("GET", "/api/conversations", null, false).statusCode())
                .isEqualTo(403);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void passwordChangeRevokesOtherDevicesAndConversationIsPersistent() throws Exception {
        var one = new Browser();
        var two = new Browser();
        assertThat(one.login("admin", "Initial-password-11").statusCode()).isEqualTo(200);
        assertThat(two.login("admin", "Initial-password-11").statusCode()).isEqualTo(200);
        one.csrf();
        assertThat(
                        one.request(
                                        "POST",
                                        "/api/auth/password",
                                        "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Initial-password-11\"}",
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(one.request("GET", "/api/conversations", null, false).statusCode())
                .isEqualTo(403);
        assertThat(
                        one.request(
                                        "POST",
                                        "/api/auth/password",
                                        "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Changed-password-11\"}",
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(two.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(one.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
        one.login("admin", "Changed-password-11");
        one.csrf();
        var created = one.request("POST", "/api/conversations", "{}", true);
        assertThat(created.statusCode()).isEqualTo(201);
        var id = json.readTree(created.body()).get("id").asString();
        assertThat(
                        json.readTree(
                                        one.request("GET", "/api/conversations/" + id, null, false)
                                                .body())
                                .get("title")
                                .asString())
                .isEqualTo("新会话");
        assertThat(one.request("GET", "/api/conversations", null, false).body()).contains(id);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void adminCreatesResetsAndDisablesUserWithoutOwnerBypass() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Changed-password-11");
        admin.csrf();
        var created =
                admin.request(
                        "POST",
                        "/api/admin/users",
                        "{\"username\":\"alice\",\"displayName\":\"Alice\"}",
                        true);
        assertThat(created.statusCode()).isEqualTo(201);
        var user = json.readTree(created.body());
        var id = user.get("user").get("id").asString();
        var userBrowser = new Browser();
        userBrowser.login("alice", user.get("temporaryPassword").asString());
        userBrowser.csrf();
        assertThat(
                        userBrowser
                                .request(
                                        "POST",
                                        "/api/auth/password",
                                        json.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        user.get("temporaryPassword").asString(),
                                                        "newPassword",
                                                        "Alice-password-11")),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        userBrowser.login("alice", "Alice-password-11");
        userBrowser.csrf();
        assertThat(userBrowser.request("GET", "/api/admin/users", null, false).statusCode())
                .isEqualTo(403);
        var conversation = userBrowser.request("POST", "/api/conversations", "{}", true);
        var conversationId = json.readTree(conversation.body()).get("id").asString();
        assertThat(
                        admin.request("GET", "/api/conversations/" + conversationId, null, false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(admin.request("GET", "/api/conversations", null, false).body())
                .doesNotContain(conversationId);
        assertThat(
                        userBrowser
                                .request(
                                        "POST",
                                        "/api/conversations",
                                        "{\"ownerId\":\"forged\"}",
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        admin.request(
                                        "PATCH",
                                        "/api/admin/users/" + id + "/status",
                                        "{\"enabled\":false}",
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        admin.request(
                                        "PATCH",
                                        "/api/admin/users/" + id + "/status",
                                        "{\"enabled\":true}",
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(userBrowser.request("GET", "/api/auth/me", null, false).statusCode())
                .isEqualTo(401);
        userBrowser.login("alice", "Alice-password-11");
        var reset = admin.request("POST", "/api/admin/users/" + id + "/reset-password", "{}", true);
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(userBrowser.request("GET", "/api/auth/me", null, false).statusCode())
                .isEqualTo(401);
        assertThat(
                        userBrowser
                                .login(
                                        "alice",
                                        json.readTree(reset.body())
                                                .get("temporaryPassword")
                                                .asString())
                                .statusCode())
                .isEqualTo(200);
        assertThat(userBrowser.request("GET", "/api/conversations", null, false).statusCode())
                .isEqualTo(403);
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"alice\",\"displayName\":\"Again\"}",
                                        true)
                                .statusCode())
                .isEqualTo(409);
        var adminId =
                json.readTree(admin.request("GET", "/api/auth/me", null, false).body())
                        .get("id")
                        .asString();
        assertThat(
                        admin.request(
                                        "PATCH",
                                        "/api/admin/users/" + adminId + "/status",
                                        "{\"enabled\":false}",
                                        true)
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void csrfRotationCookieAndLogoutAreEnforced() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String anonymous = browser.cookies.getCookieStore().getCookies().toString();
        assertThat(
                        browser.request(
                                        "POST",
                                        "/api/auth/login",
                                        "username=admin&password=Changed-password-11",
                                        false)
                                .statusCode())
                .isEqualTo(403);
        var login = browser.login("admin", "Changed-password-11");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(browser.cookies.getCookieStore().getCookies().toString())
                .isNotEqualTo(anonymous);
        assertThat(login.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("HttpOnly", "SameSite=Lax", "Max-Age=2592000");
        assertThat(login.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(browser.request("POST", "/api/conversations", "{}", false).statusCode())
                .isEqualTo(403);
        assertThat(browser.request("POST", "/api/auth/logout", null, false).statusCode())
                .isEqualTo(403);
        browser.csrf();
        assertThat(browser.request("POST", "/api/auth/logout", null, true).statusCode())
                .isEqualTo(204);
        assertThat(browser.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void fiveFailuresLockForFifteenMinutesAndSuccessClearsFailures() throws Exception {
        var browser = new Browser();
        for (int i = 0; i < 5; i++)
            assertThat(browser.login("admin", "bad-password").statusCode()).isEqualTo(401);
        assertThat(browser.login("admin", "Changed-password-11").statusCode()).isEqualTo(401);
        clock.advance(java.time.Duration.ofMinutes(15));
        assertThat(browser.login("admin", "Changed-password-11").statusCode()).isEqualTo(200);
        for (int i = 0; i < 4; i++)
            assertThat(new Browser().login("admin", "bad-password").statusCode()).isEqualTo(401);
        assertThat(new Browser().login("admin", "Changed-password-11").statusCode()).isEqualTo(200);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void idleAndAbsoluteSessionLimitsCannotBeExtendedByActivity() throws Exception {
        var idle = new Browser();
        idle.login("admin", "Changed-password-11");
        clock.advance(java.time.Duration.ofDays(7));
        assertThat(idle.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
        var active = new Browser();
        active.login("admin", "Changed-password-11");
        for (int i = 0; i < 5; i++) {
            clock.advance(java.time.Duration.ofDays(5));
            assertThat(active.request("GET", "/api/auth/me", null, false).statusCode())
                    .isEqualTo(200);
        }
        clock.advance(java.time.Duration.ofDays(5));
        assertThat(active.request("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void validatesPasswordBoundariesPaginationAndForgedRole() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Changed-password-11");
        admin.csrf();
        for (String password : List.of("short", "界".repeat(25))) {
            assertThat(
                            admin.request(
                                            "POST",
                                            "/api/auth/password",
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "currentPassword",
                                                            "Changed-password-11",
                                                            "newPassword",
                                                            password)),
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
        }
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/auth/password",
                                        "{\"currentPassword\":\"wrong\",\"newPassword\":\"Long-enough-password\"}",
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(admin.request("GET", "/api/conversations?page=-1", null, false).statusCode())
                .isEqualTo(400);
        assertThat(admin.request("GET", "/api/conversations?size=101", null, false).statusCode())
                .isEqualTo(400);
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/admin/users",
                                        "{\"username\":\"forged\",\"displayName\":\"Forged\",\"role\":\"ADMIN\"}",
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(admin.request("GET", "/api/admin/users", null, false).body())
                .doesNotContain("password_hash", "temporaryPassword", "authVersion");
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    void dependencyOutagesFailClosedWith503AndRecover() throws Exception {
        var browser = new Browser();
        browser.login("admin", "Changed-password-11");
        for (var container : List.of(MYSQL, REDIS)) {
            container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
            try {
                var response = browser.request("GET", "/api/auth/me", null, false);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).isEqualTo("{\"error\":\"service_unavailable\"}");
                assertThat(
                                browser.request("GET", "/actuator/health/liveness", null, false)
                                        .statusCode())
                        .isEqualTo(200);
            } finally {
                container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
            }
            assertThat(browser.request("GET", "/api/auth/me", null, false).statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    void unicodePasswordBoundaryAndPaginationAreUsable() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Changed-password-11");
        admin.csrf();
        var created =
                admin.request(
                        "POST",
                        "/api/admin/users",
                        "{\"username\":\"boundary\",\"displayName\":\"Boundary\"}",
                        true);
        var temporary = json.readTree(created.body()).get("temporaryPassword").asString();
        var ordinary = new Browser();
        ordinary.login("boundary", temporary);
        ordinary.csrf();
        var password = "界".repeat(24);
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/auth/password",
                                        json.writeValueAsString(
                                                Map.of(
                                                        "currentPassword",
                                                        temporary,
                                                        "newPassword",
                                                        password)),
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(ordinary.login("boundary", password).statusCode()).isEqualTo(200);
        var adminConversation =
                json.readTree(admin.request("GET", "/api/conversations", null, false).body())
                        .get("items")
                        .get(0)
                        .get("id")
                        .asString();
        assertThat(
                        ordinary.request(
                                        "GET",
                                        "/api/conversations/" + adminConversation,
                                        null,
                                        false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(ordinary.request("GET", "/api/conversations", null, false).body())
                .doesNotContain(adminConversation);
        ordinary.csrf();
        for (int i = 0; i < 3; i++)
            assertThat(ordinary.request("POST", "/api/conversations", "{}", true).statusCode())
                    .isEqualTo(201);
        var ids = new HashSet<String>();
        for (int i = 0; i < 3; i++) {
            var result =
                    json.readTree(
                            ordinary.request(
                                            "GET",
                                            "/api/conversations?size=1&page=" + i,
                                            null,
                                            false)
                                    .body());
            assertThat(result.get("items").size()).isEqualTo(1);
            assertThat(result.get("hasMore").asBoolean()).isEqualTo(i < 2);
            ids.add(result.get("items").get(0).get("id").asString());
        }
        assertThat(ids).hasSize(3);
        assertThat(
                        json.readTree(
                                        admin.request("GET", "/api/admin/users?size=1", null, false)
                                                .body())
                                .get("hasMore")
                                .asBoolean())
                .isTrue();
    }
}
