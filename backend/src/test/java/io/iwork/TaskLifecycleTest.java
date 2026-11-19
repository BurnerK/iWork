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
            "iwork.files-directory=target/task-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class TaskLifecycleTest {
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

    static final java.util.concurrent.Semaphore releaseQueueGate =
            new java.util.concurrent.Semaphore(0);
    static final java.util.concurrent.BlockingQueue<String> queuedExecutions =
            new java.util.concurrent.LinkedBlockingQueue<>();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExecutionConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.iwork.conversation.TaskRunner runner() {
            return input -> {
                if (input.message().startsWith("queue ")) {
                    if (!releaseQueueGate.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Queue gate timed out");
                    if (input.message().equals("queue failure"))
                        throw new IllegalStateException("Fixture failure");
                }
                if (Set.of("second", "third").contains(input.message()))
                    queuedExecutions.add(input.message());
                if (input.message().equals("hold")) Thread.sleep(30000);
                if (input.message().equals("fail"))
                    throw new IllegalStateException("private-provider-detail");
                if (input.message().equals("late")) {
                    long until = System.nanoTime() + java.time.Duration.ofMillis(500).toNanos();
                    while (System.nanoTime() < until) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                return "Fixture reply";
            };
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Task submission is persistent and idempotent")
    void taskSubmissionIsPersistentAndIdempotent() throws Exception {
        var admin = new Browser();
        admin.login("admin", "Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Changed-task-password\"}",
                true);
        admin.login("admin", "Changed-task-password");
        admin.csrf();
        var conversation = admin.request("POST", "/api/conversations", "{}", true);
        assertThat(conversation.statusCode()).isEqualTo(201);
        var cid = json.readTree(conversation.body()).get("id").asString();
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
                                        true)),
                        true);
        assertThat(model.statusCode()).isEqualTo(201);
        var modelId = json.readTree(model.body()).get("id").asString();
        var uid =
                json.readTree(admin.request("GET", "/api/auth/me", null, false).body())
                        .get("id")
                        .asString();
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(modelId))),
                true);
        var uploaded =
                admin.client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/api/materials"))
                                .header("X-CSRF-TOKEN", admin.csrf)
                                .header("X-File-Name", "source.txt")
                                .header("Content-Type", "application/octet-stream")
                                .POST(HttpRequest.BodyPublishers.ofString("source material"))
                                .build(),
                        io.iwork.ApiTestResponse.body());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        var materialId = json.readTree(uploaded.body()).get("id").asString();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                "request-one",
                                "modelId",
                                modelId,
                                "message",
                                "hold",
                                "materialIds",
                                List.of(materialId)));
        var response = admin.request("POST", "/api/conversations/" + cid + "/tasks", body, true);
        assertThat(response.statusCode()).isEqualTo(202);
        var taskId = json.readTree(response.body()).get("id").asString();
        assertThat(admin.request("GET", "/api/tasks/" + taskId + "/materials", null, false).body())
                .contains(materialId, "source.txt");
        var changedMaterials =
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                "request-one",
                                "modelId",
                                modelId,
                                "message",
                                "hold",
                                "materialIds",
                                List.of()));
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/conversations/" + cid + "/tasks",
                                        changedMaterials,
                                        true)
                                .statusCode())
                .isEqualTo(409);

        assertThat(admin.request("POST", "/api/conversations/" + cid + "/tasks", body, true).body())
                .contains(taskId);
        assertThat(admin.request("GET", "/api/tasks/" + taskId, null, false).body())
                .contains("RUNNING");
        assertThat(
                        admin.request("GET", "/api/conversations/" + cid + "/messages", null, false)
                                .body())
                .contains("hold");
        var conflict =
                json.writeValueAsString(
                        Map.of(
                                "requestId",
                                "request-one",
                                "modelId",
                                modelId,
                                "message",
                                "changed"));
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/conversations/" + cid + "/tasks",
                                        conflict,
                                        true)
                                .statusCode())
                .isEqualTo(409);
        var another =
                json.writeValueAsString(
                        Map.of("requestId", "request-two", "modelId", modelId, "message", "Hello"));
        assertThat(
                        admin.request("POST", "/api/conversations/" + cid + "/tasks", another, true)
                                .statusCode())
                .isEqualTo(409);
        assertThat(admin.request("POST", "/api/tasks/" + taskId + "/cancel", "{}", true).body())
                .contains("CANCELLED");
        assertThat(admin.request("POST", "/api/conversations/" + cid + "/tasks", body, true).body())
                .contains("CANCELLED", taskId);
        awaitTask(admin, taskId, "executionState", "STOPPED");
        var concurrentBody =
                json.writeValueAsString(
                        Map.of("requestId", "concurrent", "modelId", modelId, "message", "hold"));
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++)
                submissions.add(
                        pool.submit(
                                () ->
                                        admin.request(
                                                "POST",
                                                "/api/conversations/" + cid + "/tasks",
                                                concurrentBody,
                                                true)));
            var ids = new HashSet<String>();
            for (var submitted : submissions) {
                var result = submitted.get();
                assertThat(result.statusCode()).isEqualTo(202);
                ids.add(json.readTree(result.body()).get("id").asString());
            }
            assertThat(ids).hasSize(1);
            taskId = ids.iterator().next();
        }
        var secondConversation = admin.request("POST", "/api/conversations", "{}", true);
        var cid2 = json.readTree(secondConversation.body()).get("id").asString();
        var running2 =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid2 + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        "parallel",
                                        "modelId",
                                        modelId,
                                        "message",
                                        "hold")),
                        true);
        assertThat(running2.statusCode()).isEqualTo(202);
        var task2 = json.readTree(running2.body()).get("id").asString();
        var thirdConversation = admin.request("POST", "/api/conversations", "{}", true);
        var cid3 = json.readTree(thirdConversation.body()).get("id").asString();
        assertThat(
                        admin.request(
                                        "POST",
                                        "/api/conversations/" + cid3 + "/tasks",
                                        json.writeValueAsString(
                                                Map.of(
                                                        "requestId",
                                                        "over-capacity",
                                                        "modelId",
                                                        modelId,
                                                        "message",
                                                        "hold")),
                                        true)
                                .statusCode())
                .isEqualTo(429);
        admin.request("POST", "/api/tasks/" + taskId + "/cancel", "{}", true);
        admin.request("POST", "/api/tasks/" + task2 + "/cancel", "{}", true);
        awaitTask(admin, taskId, "executionState", "STOPPED");
        awaitTask(admin, task2, "executionState", "STOPPED");
        var completed =
                admin.request("POST", "/api/conversations/" + cid + "/tasks", another, true);
        assertThat(completed.statusCode()).isEqualTo(202);
        var completedId = json.readTree(completed.body()).get("id").asString();
        awaitTask(admin, completedId, "status", "COMPLETED");
        assertThat(
                        admin.request("POST", "/api/tasks/" + completedId + "/cancel", "{}", true)
                                .body())
                .contains("COMPLETED");
        assertThat(
                        admin.request("GET", "/api/conversations/" + cid + "/messages", null, false)
                                .body())
                .contains("Fixture reply");
        var failure =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        "failure",
                                        "modelId",
                                        modelId,
                                        "message",
                                        "fail")),
                        true);
        var failedId = json.readTree(failure.body()).get("id").asString();
        awaitTask(admin, failedId, "status", "FAILED");
        assertThat(admin.request("GET", "/api/tasks/" + failedId, null, false).body())
                .doesNotContain("private-provider-detail");
        var late =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid2 + "/tasks",
                        json.writeValueAsString(
                                Map.of("requestId", "late", "modelId", modelId, "message", "late")),
                        true);
        var lateId = json.readTree(late.body()).get("id").asString();
        admin.request("POST", "/api/tasks/" + lateId + "/cancel", "{}", true);
        awaitTask(admin, lateId, "executionState", "STOPPED");
        assertThat(admin.request("GET", "/api/tasks/" + lateId, null, false).body())
                .contains("CANCELLED");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/conversations/" + cid2 + "/messages",
                                        null,
                                        false)
                                .body())
                .doesNotContain("Fixture reply");

        var createdUser =
                admin.request(
                        "POST",
                        "/api/admin/users",
                        "{\"username\":\"task-user\",\"displayName\":\"Task user\"}",
                        true);
        var user = json.readTree(createdUser.body());
        var userId = user.get("user").get("id").asString();
        var ordinary = new Browser();
        ordinary.login("task-user", user.get("temporaryPassword").asString());
        ordinary.csrf();
        ordinary.request(
                "POST",
                "/api/auth/password",
                json.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                user.get("temporaryPassword").asString(),
                                "newPassword",
                                "Task-user-password")),
                true);
        ordinary.login("task-user", "Task-user-password");
        ordinary.csrf();
        assertThat(ordinary.request("GET", "/api/tasks/" + taskId, null, false).statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request("POST", "/api/tasks/" + taskId + "/cancel", "{}", true)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request(
                                        "GET",
                                        "/api/conversations/" + cid + "/messages",
                                        null,
                                        false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        ordinary.request("POST", "/api/conversations/" + cid + "/tasks", body, true)
                                .statusCode())
                .isEqualTo(404);
        var ownConversation = ordinary.request("POST", "/api/conversations", "{}", true);
        var ownId = json.readTree(ownConversation.body()).get("id").asString();
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/conversations/" + ownId + "/tasks",
                                        body,
                                        true)
                                .statusCode())
                .isEqualTo(404);
        var ownBody =
                json.writeValueAsString(
                        Map.of("requestId", "request-one", "modelId", modelId, "message", "hold"));
        assertThat(
                        ordinary.request(
                                        "POST",
                                        "/api/conversations/" + ownId + "/tasks",
                                        ownBody,
                                        true)
                                .statusCode())
                .isEqualTo(403);
        admin.request(
                "PUT",
                "/api/admin/users/" + userId + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(modelId))),
                true);
        var sameKeyOtherOwner =
                ordinary.request("POST", "/api/conversations/" + ownId + "/tasks", ownBody, true);
        assertThat(sameKeyOtherOwner.statusCode()).isEqualTo(202);
        var ownTaskId = json.readTree(sameKeyOtherOwner.body()).get("id").asString();
        assertThat(ownTaskId).isNotEqualTo(taskId);
        assertThat(admin.request("GET", "/api/tasks/" + ownTaskId, null, false).statusCode())
                .isEqualTo(404);
        ordinary.request("POST", "/api/tasks/" + ownTaskId + "/cancel", "{}", true);
        awaitTask(ordinary, ownTaskId, "executionState", "STOPPED");

        verifyFollowUpQueue(cid, cid2, modelId, materialId, uid);

        var storageFailure =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid3 + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        "storage-failure",
                                        "modelId",
                                        modelId,
                                        "message",
                                        "late")),
                        true);
        var storageId = json.readTree(storageFailure.body()).get("id").asString();
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            Thread.sleep(20000);
        } finally {
            MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
        awaitTask(admin, storageId, "status", "FAILED");
    }

    private void verifyFollowUpQueue(
            String cid, String otherCid, String modelId, String materialId, String ownerId)
            throws Exception {
        String path = "/api/conversations/" + cid + "/tasks";
        String otherPath = "/api/conversations/" + otherCid + "/tasks";
        var browser = new Browser();
        browser.login("admin", "Changed-task-password");
        browser.csrf();
        var first = submitFollowUp(browser, path, modelId, "queue-first", "queue gate", List.of());
        assertThat(first.get("status").asString()).isEqualTo("RUNNING");
        // Discard the first receipt. Recovery and retry must refer to the same queued request.
        submitFollowUp(browser, path, modelId, "queue-second", "second", List.of(materialId));
        var recent =
                json.readTree(
                        browser.request("GET", path + "?requestId=queue-second", null, false)
                                .body());
        assertThat(recent.get("confirmedRequestId").asString()).isEqualTo("queue-second");
        assertThat(recent.get("items").valueStream().map(item -> item.get("requestId").asString()))
                .doesNotContain("queue-second");
        var retry =
                submitFollowUp(
                        browser, path, modelId, "queue-second", "second", List.of(materialId));
        var third = submitFollowUp(browser, path, modelId, "queue-third", "third", List.of());
        assertThat(retry.get("status").asString()).isEqualTo("QUEUED");
        assertThat(third.get("status").asString()).isEqualTo("QUEUED");
        String secondId = retry.get("id").asString();
        var other =
                submitFollowUp(
                        browser, otherPath, modelId, "queue-other", "independent", List.of());
        assertThat(other.get("status").asString()).isEqualTo("RUNNING");

        browser.client.close();
        releaseQueueGate.release();
        assertThat(queuedExecutions.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo("second");
        assertThat(queuedExecutions.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo("third");
        var reopened = new Browser();
        reopened.login("admin", "Changed-task-password");
        reopened.csrf();
        awaitTask(reopened, third.get("id").asString(), "executionState", "STOPPED");
        awaitTask(reopened, third.get("id").asString(), "status", "COMPLETED");
        awaitTask(reopened, other.get("id").asString(), "executionState", "STOPPED");
        var messages =
                json.readTree(
                                reopened.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/messages",
                                                null,
                                                false)
                                        .body())
                        .get("items");
        assertThat(
                        messages.valueStream()
                                .filter(message -> message.get("role").asString().equals("user"))
                                .map(message -> message.get("content").asString())
                                .toList())
                .endsWith("queue gate", "second", "third");
        assertThat(
                        messages.valueStream()
                                .filter(
                                        message ->
                                                message.get("taskId").asString().equals(secondId))
                                .count())
                .isEqualTo(2);
        assertThat(
                        reopened.request(
                                        "GET", "/api/tasks/" + secondId + "/materials", null, false)
                                .body())
                .contains(materialId);
        assertThat(
                        submitFollowUp(
                                        reopened,
                                        path,
                                        modelId,
                                        "queue-second",
                                        "second",
                                        List.of(materialId))
                                .get("id")
                                .asString())
                .isEqualTo(secondId);

        var permissionConversation =
                json.readTree(reopened.request("POST", "/api/conversations", "{}", true).body());
        String permissionPath =
                "/api/conversations/" + permissionConversation.get("id").asString() + "/tasks";
        var gate =
                submitFollowUp(
                        reopened,
                        permissionPath,
                        modelId,
                        "permission-gate",
                        "queue gate",
                        List.of());
        var rejected =
                submitFollowUp(
                        reopened,
                        permissionPath,
                        modelId,
                        "permission-queued",
                        "queued model",
                        List.of());
        submitFollowUp(
                reopened, permissionPath, modelId, "permission-waiting", "waiting", List.of());
        String accessPath = "/api/admin/users/" + ownerId + "/model-access";
        assertThat(reopened.request("PUT", accessPath, "{\"modelIds\":[]}", true).statusCode())
                .isEqualTo(200);
        releaseQueueGate.release();
        awaitTask(reopened, gate.get("id").asString(), "status", "COMPLETED");
        awaitTask(reopened, rejected.get("id").asString(), "errorCode", "model_not_authorized");
        assertThat(
                        json.readTree(reopened.request("GET", permissionPath, null, false).body())
                                .get("queuePaused")
                                .asBoolean())
                .isTrue();
        assertThat(
                        reopened.request(
                                        "PUT",
                                        accessPath,
                                        json.writeValueAsString(
                                                Map.of("modelIds", List.of(modelId))),
                                        true)
                                .statusCode())
                .isEqualTo(200);

        submitFollowUp(reopened, otherPath, modelId, "isolated-gate", "queue gate", List.of());
        var independentFollowUp =
                submitFollowUp(
                        reopened,
                        otherPath,
                        modelId,
                        "isolated-follow-up",
                        "independent follow-up",
                        List.of());
        assertThat(independentFollowUp.get("status").asString()).isEqualTo("QUEUED");
        releaseQueueGate.release();
        awaitTask(reopened, independentFollowUp.get("id").asString(), "executionState", "STOPPED");
        awaitTask(reopened, independentFollowUp.get("id").asString(), "status", "COMPLETED");
        assertThat(
                        json.readTree(reopened.request("GET", permissionPath, null, false).body())
                                .get("queuedCount")
                                .asInt())
                .isEqualTo(1);

        for (String trigger : List.of("cancel", "failure")) {
            String target = trigger.equals("cancel") ? path : otherPath;
            var current =
                    submitFollowUp(
                            reopened,
                            target,
                            modelId,
                            "queue-" + trigger,
                            "queue " + trigger,
                            List.of());
            String currentId = current.get("id").asString();
            var waiting =
                    submitFollowUp(
                            reopened, target, modelId, "waiting-" + trigger, "waiting", List.of());
            assertThat(waiting.get("status").asString()).isEqualTo("QUEUED");
            if (trigger.equals("cancel")) {
                reopened.request("POST", "/api/tasks/" + currentId + "/cancel", "{}", true);
            } else {
                releaseQueueGate.release();
            }
            awaitTask(
                    reopened,
                    currentId,
                    "status",
                    trigger.equals("cancel") ? "CANCELLED" : "FAILED");
            var refreshed = json.readTree(reopened.request("GET", target, null, false).body());
            assertThat(refreshed.get("queuePaused").asBoolean()).isTrue();
            assertThat(refreshed.get("queuedCount").asInt()).isEqualTo(1);
            assertThat(
                            reopened.request(
                                            "GET",
                                            "/api/tasks/" + waiting.get("id").asString(),
                                            null,
                                            false)
                                    .body())
                    .contains("QUEUED");
            awaitTask(reopened, currentId, "executionState", "STOPPED");
        }
    }

    private tools.jackson.databind.JsonNode submitFollowUp(
            Browser browser,
            String path,
            String modelId,
            String requestId,
            String message,
            List<String> materials)
            throws Exception {
        var response =
                browser.request(
                        "POST",
                        path,
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        requestId,
                                        "modelId",
                                        modelId,
                                        "message",
                                        message,
                                        "materialIds",
                                        materials,
                                        "allowQueue",
                                        true)),
                        true);
        assertThat(response.statusCode()).as("response: %s", response.body()).isEqualTo(202);
        return json.readTree(response.body());
    }

    void awaitTask(Browser browser, String id, String field, String expected) throws Exception {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        String actual = "";
        while (System.nanoTime() < until) {
            var result = browser.request("GET", "/api/tasks/" + id, null, false);
            assertThat(result.statusCode()).isEqualTo(200);
            actual = json.readTree(result.body()).get(field).asString();
            if (actual.equals(expected)) return;
            Thread.sleep(20);
        }
        assertThat(actual).isEqualTo(expected);
    }
}
