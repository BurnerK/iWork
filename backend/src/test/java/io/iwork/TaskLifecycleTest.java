package io.iwork;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            "iwork.task-conversation-queue-limit=3",
            "iwork.task-account-queue-limit=6",
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

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

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

    static final Semaphore finishAgUiDocument = new Semaphore(0);
    static final AtomicInteger agUiDocumentExecutions = new AtomicInteger();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExecutionConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.iwork.conversation.TaskRunner runner(io.iwork.artifacts.DocumentService documents) {
            return input -> {
                boolean agUiReply = input.message().equals("queue AG-UI");
                boolean detachedDocument = agUiReply && input.outputMode().equals("DOCUMENT");
                if (detachedDocument) agUiDocumentExecutions.incrementAndGet();
                if (input.message().startsWith("queue ")) {
                    if (!releaseQueueGate.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Queue gate timed out");
                    if (input.message().equals("queue failure"))
                        throw new IllegalStateException("Fixture failure");
                }
                if (Set.of("second", "remove this", "third")
                        .contains(input.message().stripLeading()))
                    queuedExecutions.add(input.message());
                if (input.message().equals("hold")) Thread.sleep(30000);
                if (agUiReply) {
                    input.replyDelta().accept("reply", "第一段。");
                    if (!releaseQueueGate.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Reply gate timed out");
                    input.replyDelta().accept("reply", "第二段。");
                    if (detachedDocument) {
                        if (!finishAgUiDocument.tryAcquire(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("Document gate timed out");
                        documents.new Tools(input).prepare("周报", "# 周报\n本周进展。");
                    }
                    return "第一段。第二段。";
                }
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

        verifyQueueLimits(ordinary, userId, modelId);
        verifyFollowUpQueue(cid, cid2, modelId, materialId, uid);
        verifyAgUi(admin, modelId, materialId);
        verifyDetachedAgUiDocument(admin, modelId);

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

    private void verifyAgUi(Browser admin, String modelId, String materialId) throws Exception {
        var cid =
                json.readTree(admin.request("POST", "/api/conversations", "{}", true).body())
                        .get("id")
                        .asString();
        var path = "/api/conversations/" + cid + "/ag-ui";
        var first = agUiInput(cid, "agui-first", modelId, "queue AG-UI", List.of());
        releaseQueueGate.drainPermits();
        var response = openAgUi(admin, path, first);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("text/event-stream");
        String taskId;
        try (var lines = response.body()) {
            var events = lines.filter(line -> line.startsWith("data:")).iterator();
            assertThat(json.readTree(events.next().substring(5)).get("type").asString())
                    .isEqualTo("RUN_STARTED");
            var receipt = json.readTree(events.next().substring(5));
            assertThat(receipt.get("name").asString()).isEqualTo("iwork.task");
            taskId = receipt.get("value").get("id").asString();
            releaseQueueGate.release();
            assertThat(events.next()).contains("TEXT_MESSAGE_START");
            assertThat(events.next()).contains("TEXT_MESSAGE_CONTENT", "第一段。");
            try {
                var observer = openAgUi(admin, "/api/tasks/" + taskId + "/ag-ui", null);
                assertThat(observer.statusCode()).isEqualTo(200);
                try (var observed = observer.body()) {
                    assertThat(
                                    observed.filter(line -> line.contains("MESSAGES_SNAPSHOT"))
                                            .findFirst())
                            .hasValueSatisfying(line -> assertThat(line).contains("第一段。"));
                }
                var retry = openAgUi(admin, path, first);
                assertThat(retry.statusCode()).isEqualTo(200);
                try (var retried = retry.body()) {
                    assertThat(retried.filter(line -> line.contains("iwork.task")).findFirst())
                            .hasValueSatisfying(line -> assertThat(line).contains(taskId));
                }
                var followUp = agUiInput(cid, "agui-next", modelId, "fail", List.of(materialId));
                var queued = openAgUi(admin, path, followUp);
                assertThat(queued.statusCode()).isEqualTo(200);
                String queuedId;
                try (var queuedLines = queued.body()) {
                    var queuedReceipt =
                            queuedLines
                                    .filter(line -> line.contains("iwork.task"))
                                    .findFirst()
                                    .orElseThrow();
                    var queuedTask = json.readTree(queuedReceipt.substring(5)).get("value");
                    assertThat(queuedTask.get("status").asString()).isEqualTo("QUEUED");
                    queuedId = queuedTask.get("id").asString();
                }
                assertThat(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + queuedId + "/materials",
                                                null,
                                                false)
                                        .body())
                        .contains(materialId);
                assertThat(
                                admin.request(
                                                "GET",
                                                "/api/conversations/"
                                                        + cid
                                                        + "/tasks?requestId=agui-first",
                                                null,
                                                false)
                                        .body())
                        .contains(taskId, "agui-first");
                releaseQueueGate.release();
                var terminal = new ArrayList<String>();
                events.forEachRemaining(line -> terminal.add(line.substring(5)));
                assertThat(
                                terminal.stream()
                                        .map(line -> json.readTree(line).get("type").asString()))
                        .containsSubsequence(
                                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
                assertThat(
                                terminal.stream()
                                        .map(json::readTree)
                                        .filter(
                                                event ->
                                                        event.get("type")
                                                                .asString()
                                                                .equals("TEXT_MESSAGE_CONTENT"))
                                        .map(event -> event.get("delta").asString()))
                        .containsExactly("第二段。");
                assertThat(admin.request("POST", path, first, true).body())
                        .contains(taskId, "RUN_FINISHED");
                assertThat(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + queuedId + "/ag-ui",
                                                null,
                                                false)
                                        .body())
                        .contains("RUN_ERROR", "execution_failed")
                        .doesNotContain("RUN_FINISHED", "private-provider-detail");
            } finally {
                releaseQueueGate.drainPermits();
            }
        }
        var tasks =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/tasks",
                                                null,
                                                false)
                                        .body())
                        .get("items");
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(tasks.get(1).get("status").asString()).isEqualTo("COMPLETED");
    }

    private void verifyDetachedAgUiDocument(Browser admin, String modelId) throws Exception {
        var cid =
                json.readTree(admin.request("POST", "/api/conversations", "{}", true).body())
                        .path("id")
                        .asString();
        var conversation = "/api/conversations/" + cid;
        var documentRun =
                agUiInput(cid, "agui-document", modelId, "queue AG-UI", List.of(), "DOCUMENT");
        String taskId;
        String queuedId;
        try {
            try (var submitted = openAgUi(admin, conversation + "/ag-ui", documentRun).body()) {
                var events = submitted.filter(line -> line.startsWith("data:")).iterator();
                events.next();
                taskId =
                        json.readTree(events.next().substring(5))
                                .path("value")
                                .path("id")
                                .asString();
                releaseQueueGate.release();
                assertThat(events.next()).contains("TEXT_MESSAGE_START");
                assertThat(events.next()).contains("TEXT_MESSAGE_CONTENT", "第一段。");
            }
            var followUp = agUiInput(cid, "agui-follow-up", modelId, "follow-up", List.of());
            try (var queued = openAgUi(admin, conversation + "/ag-ui", followUp).body()) {
                var receipt =
                        json.readTree(
                                        queued.filter(line -> line.contains("iwork.task"))
                                                .findFirst()
                                                .orElseThrow()
                                                .substring(5))
                                .path("value");
                assertThat(receipt.path("status").asString()).isEqualTo("QUEUED");
                queuedId = receipt.path("id").asString();
            }
            var observation = "/api/tasks/" + taskId + "/ag-ui";
            try (var first = openAgUi(admin, observation, null).body();
                    var second = openAgUi(admin, observation, null).body()) {
                var firstEvents = first.filter(line -> line.startsWith("data:")).iterator();
                assertThat(readAgUiSnapshot(firstEvents, taskId)).isEqualTo("第一段。");
                releaseQueueGate.release();
                assertThat(readAgUiDelta(firstEvents)).isEqualTo("第二段。");

                // The second observer can sample the new text before or after its initial snapshot.
                var secondEvents = second.filter(line -> line.startsWith("data:")).iterator();
                String content = readAgUiSnapshot(secondEvents, taskId);
                if (content.equals("第一段。")) content += readAgUiDelta(secondEvents);
                assertThat(content).isEqualTo("第一段。第二段。");
            }
        } finally {
            finishAgUiDocument.release();
        }
        awaitTask(admin, taskId, "status", "COMPLETED");
        awaitTask(admin, queuedId, "status", "COMPLETED");
        assertThat(agUiDocumentExecutions.get()).isEqualTo(1);
        var versions =
                json.readTree(admin.request("GET", conversation + "/documents", null, false).body())
                        .path("items");
        assertThat(versions).hasSize(1);
        var versionId = versions.get(0).path("versionId").asString();
        assertThat(versions.get(0).path("versionNumber").asInt()).isEqualTo(1);
        var history =
                json.readTree(admin.request("GET", conversation + "/messages", null, false).body())
                        .path("items");
        assertThat(history).hasSize(4);
        assertThat(history.get(1).path("content").asString()).isEqualTo("第一段。第二段。");
        assertThat(history.get(1).path("publishedVersion").path("versionId").asString())
                .isEqualTo(versionId);
        assertThat(history.get(3).path("content").asString()).isEqualTo("Fixture reply");
        var restored = admin.request("GET", "/api/tasks/" + taskId + "/ag-ui", null, false);
        assertThat(restored.body()).contains("MESSAGES_SNAPSHOT", "第一段。第二段。", "RUN_FINISHED");
    }

    private String readAgUiSnapshot(Iterator<String> events, String taskId) {
        assertThat(events.next()).contains("RUN_STARTED");
        assertThat(events.next()).contains(taskId, "RUNNING");
        var snapshot = json.readTree(events.next().substring(5));
        assertThat(snapshot.path("type").asString()).isEqualTo("MESSAGES_SNAPSHOT");
        return snapshot.path("messages").path(0).path("content").asString();
    }

    private String readAgUiDelta(Iterator<String> events) {
        assertThat(events.next()).contains("TEXT_MESSAGE_START");
        var delta = json.readTree(events.next().substring(5));
        assertThat(delta.path("type").asString()).isEqualTo("TEXT_MESSAGE_CONTENT");
        return delta.path("delta").asString();
    }

    private HttpResponse<java.util.stream.Stream<String>> openAgUi(
            Browser browser, String path, String body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Accept", "text/event-stream");
        if (body != null)
            request.header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", browser.csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        return browser.client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
    }

    private String agUiInput(
            String threadId,
            String runId,
            String modelId,
            String message,
            List<String> materialIds) {
        return agUiInput(threadId, runId, modelId, message, materialIds, "TEXT");
    }

    private String agUiInput(
            String threadId,
            String runId,
            String modelId,
            String message,
            List<String> materialIds,
            String outputMode) {
        return json.writeValueAsString(
                Map.of(
                        "threadId",
                        threadId,
                        "runId",
                        runId,
                        "state",
                        Map.of(),
                        "messages",
                        List.of(Map.of("id", runId, "role", "user", "content", message)),
                        "tools",
                        List.of(),
                        "context",
                        List.of(),
                        "forwardedProps",
                        Map.of(
                                "modelId",
                                modelId,
                                "materialIds",
                                materialIds,
                                "outputMode",
                                outputMode)));
    }

    private void verifyQueueLimits(Browser browser, String ownerId, String modelId)
            throws Exception {
        String first = pausedQueue(browser, ownerId, modelId, 1);
        var conversationRace = queueRace(browser, modelId, first, first, first);
        assertThat(conversationRace)
                .extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(202, 202, 429);
        var accepted =
                json.readTree(
                        conversationRace.stream()
                                .filter(result -> result.statusCode() == 202)
                                .findFirst()
                                .orElseThrow()
                                .body());
        var replay =
                submitFollowUp(
                        browser,
                        first,
                        modelId,
                        accepted.get("requestId").asString(),
                        "concurrent queue fixture",
                        List.of());
        assertThat(replay.get("id").asString()).isEqualTo(accepted.get("id").asString());
        assertThat(
                        json.readTree(browser.request("GET", first, null, false).body())
                                .get("queuedCount")
                                .asInt())
                .isEqualTo(3);

        String second = pausedQueue(browser, ownerId, modelId, 1);
        String third = pausedQueue(browser, ownerId, modelId, 1);
        assertThat(queueRace(browser, modelId, second, third))
                .extracting(HttpResponse::statusCode)
                .containsExactlyInAnyOrder(202, 429);

        String legacy = pausedQueue(browser, ownerId, modelId, 21);
        for (int page = 0; page < 3; page++) {
            var response = browser.request("GET", legacy + "?queuePage=" + page, null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.get("queuedCount").asInt()).isEqualTo(21);
            assertThat(body.get("queuePaused").asBoolean()).isTrue();
            assertThat(body.get("queuedItems").size()).isEqualTo(List.of(20, 1, 0).get(page));
            if (page == 1)
                assertThat(body.get("queuedItems").get(0).get("summary").asString())
                        .isEqualTo("legacy 20");
        }
        assertThat(browser.request("GET", legacy + "?queuePage=-1", null, false).statusCode())
                .isEqualTo(400);
    }

    private String pausedQueue(Browser browser, String ownerId, String modelId, int entries)
            throws Exception {
        var conversation = browser.request("POST", "/api/conversations", "{}", true);
        assertThat(conversation.statusCode()).isEqualTo(201);
        var id = json.readTree(conversation.body()).get("id").asString();
        db.update("UPDATE conversations SET queue_paused=TRUE WHERE id=?", id);
        for (int i = 0; i < entries; i++)
            db.update(
                    """
                    INSERT INTO tasks(id,owner_id,conversation_id,model_id,request_id,request_hash,
                        status,execution_state,queued_message,queued_auth_version)
                    VALUES(?,?,?,?,?,?,'QUEUED','WAITING',?,1)
                    """,
                    UUID.randomUUID().toString(),
                    ownerId,
                    id,
                    modelId,
                    UUID.randomUUID().toString(),
                    "0".repeat(64),
                    "legacy " + i);
        return "/api/conversations/" + id + "/tasks";
    }

    private List<HttpResponse<String>> queueRace(Browser browser, String modelId, String... paths)
            throws Exception {
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(1);
            var pending = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (var path : paths)
                pending.add(
                        pool.submit(
                                () -> {
                                    ready.await();
                                    return requestFollowUp(
                                            browser,
                                            path,
                                            modelId,
                                            UUID.randomUUID().toString(),
                                            "concurrent queue fixture",
                                            List.of());
                                }));
            ready.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var response : pending)
                results.add(response.get(10, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
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
        var removed =
                submitFollowUp(browser, path, modelId, "queue-removed", "remove this", List.of());
        String thirdMessage = "\n ".repeat(100) + "third";
        var third = submitFollowUp(browser, path, modelId, "queue-third", thirdMessage, List.of());
        assertThat(retry.get("status").asString()).isEqualTo("QUEUED");
        assertThat(third.get("status").asString()).isEqualTo("QUEUED");
        String secondId = retry.get("id").asString();
        var other =
                submitFollowUp(
                        browser, otherPath, modelId, "queue-other", "independent", List.of());
        assertThat(other.get("status").asString()).isEqualTo("RUNNING");

        String removePath = "/queue/" + removed.get("id").asString();
        assertThat(browser.request("DELETE", otherPath + removePath, null, true).statusCode())
                .isEqualTo(404);
        assertThat(browser.request("DELETE", path + removePath, null, false).statusCode())
                .isEqualTo(403);
        assertThat(browser.request("DELETE", path + removePath, null, true).statusCode())
                .isEqualTo(204);
        assertThat(browser.request("DELETE", path + removePath, null, true).statusCode())
                .isEqualTo(204);
        assertThat(
                        submitFollowUp(
                                        browser,
                                        path,
                                        modelId,
                                        "queue-removed",
                                        "remove this",
                                        List.of())
                                .get("id")
                                .asString())
                .isEqualTo(removed.get("id").asString());
        var remaining = json.readTree(browser.request("GET", path, null, false).body());
        assertThat(remaining.get("queuedCount").asInt()).isEqualTo(2);
        assertThat(
                        remaining
                                .get("queuedItems")
                                .valueStream()
                                .map(item -> item.get("summary").asString()))
                .containsExactly("second", "third");
        assertThat(
                        remaining
                                .get("items")
                                .valueStream()
                                .map(item -> item.get("requestId").asString()))
                .doesNotContain("queue-removed");

        browser.client.close();
        releaseQueueGate.release();
        assertThat(queuedExecutions.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo("second");
        assertThat(queuedExecutions.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(thirdMessage);
        var reopened = new Browser();
        reopened.login("admin", "Changed-task-password");
        reopened.csrf();
        awaitTask(reopened, third.get("id").asString(), "executionState", "STOPPED");
        awaitTask(reopened, third.get("id").asString(), "status", "COMPLETED");
        assertThat(queuedExecutions).isEmpty();
        assertThat(reopened.request("DELETE", path + "/queue/" + secondId, null, true).statusCode())
                .isEqualTo(409);
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
                .endsWith("queue gate", "second", thirdMessage);
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
        var response = requestFollowUp(browser, path, modelId, requestId, message, materials);
        assertThat(response.statusCode()).as("response: %s", response.body()).isEqualTo(202);
        return json.readTree(response.body());
    }

    private HttpResponse<String> requestFollowUp(
            Browser browser,
            String path,
            String modelId,
            String requestId,
            String message,
            List<String> materials)
            throws Exception {
        return browser.request(
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
