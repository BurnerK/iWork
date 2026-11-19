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

@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/model-execution-files",
            "iwork.cookie-secure=false",
            "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12",
            "iwork.bootstrap.username=admin",
            "iwork.bootstrap.password=Initial-password-11"
        })
class ModelExecutionTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.7")
                    .withUrlParam("connectTimeout", "3000")
                    .withUrlParam("socketTimeout", "3000");

    @SuppressWarnings("resource") // JUnit manages the static container lifecycle.
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

        void login(String password) throws Exception {
            csrf();
            request("POST", "/api/auth/login", "username=admin&password=" + password, true);
        }
    }

    static final java.util.concurrent.atomic.AtomicInteger cancellations =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger requests =
            new java.util.concurrent.atomic.AtomicInteger();
    static final reactor.core.publisher.Sinks.Many<String> storageStream =
            reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
    static volatile java.util.concurrent.CountDownLatch auxiliaryGate =
            new java.util.concurrent.CountDownLatch(0);
    @org.springframework.beans.factory.annotation.Autowired io.iwork.model.ModelCallLedger ledger;
    @org.springframework.beans.factory.annotation.Autowired io.iwork.model.GovernedModel models;

    @org.springframework.beans.factory.annotation.Autowired
    io.iwork.identity.IdentityService identities;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.iwork.conversation.TaskRunner composedRunner(
                io.iwork.agent.HarnessTaskRunner runner, io.iwork.model.GovernedModel models) {
            return input -> {
                var reply = runner.execute(input);
                if (input.message().contains("auxiliary")) {
                    auxiliaryGate.await();
                    var message =
                            io.agentscope.core.message.Msg.builder()
                                    .name("user")
                                    .role(io.agentscope.core.message.MsgRole.USER)
                                    .textContent("摘要")
                                    .build();
                    models.forTask(input, "SUMMARY").stream(List.of(message), List.of(), null)
                            .blockLast();
                    models.forTask(input, "OVERFLOW").stream(List.of(message), List.of(), null)
                            .blockLast();
                }
                if (input.message().contains("attempt-limit")) {
                    var message =
                            io.agentscope.core.message.Msg.builder()
                                    .name("user")
                                    .role(io.agentscope.core.message.MsgRole.USER)
                                    .textContent("摘要")
                                    .build();
                    for (int i = 0; i < 4; i++)
                        models.forTask(input, "SUMMARY").stream(List.of(message), List.of(), null)
                                .blockLast();
                }
                return reply;
            };
        }

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
                    requests.incrementAndGet();
                    assertThat(request.getBody())
                            .doesNotContain(
                                    "\"tools\"",
                                    "shell",
                                    "read_file",
                                    "web_search",
                                    "subagent",
                                    "plan_enter")
                            .contains("2048", "include_usage");
                    assertThat(request.getBody().split("\"stream_options\"", -1)).hasSize(2);
                    String current = "";
                    for (var message :
                            new ObjectMapper().readTree(request.getBody()).path("messages"))
                        if (message.path("role").asString().equals("user"))
                            current = message.path("content").asString();
                    String content =
                            "{\"id\":\"provider-one\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"模型完成\"},\"finish_reason\":null}]}";
                    String usage =
                            "{\"id\":\"provider-one\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}";
                    String end =
                            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
                    if (current.contains("storage"))
                        return reactor.core.publisher.Flux.just(
                                        content.replace(
                                                "\"content\":",
                                                "\"reasoning_content\":\"private-reasoning-fixture\",\"content\":"))
                                .concatWith(storageStream.asFlux());
                    if (current.contains("zero-usage"))
                        return reactor.core.publisher.Flux.just(
                                content,
                                "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}",
                                end,
                                "[DONE]");
                    if (current.contains("silent"))
                        return reactor.core.publisher.Flux.error(
                                new IllegalStateException("no observed output"));
                    if (current.contains("hold-known"))
                        return reactor.core.publisher.Flux.just(content, usage)
                                .concatWith(reactor.core.publisher.Flux.never())
                                .doOnCancel(cancellations::incrementAndGet);
                    if (current.contains("hold"))
                        return reactor.core.publisher.Flux.just(content)
                                .concatWith(reactor.core.publisher.Flux.never())
                                .doOnCancel(cancellations::incrementAndGet);
                    if (current.contains("missing"))
                        return reactor.core.publisher.Flux.just(content, end, "[DONE]");
                    if (current.contains("partial"))
                        return reactor.core.publisher.Flux.just(
                                content,
                                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100}}",
                                end,
                                "[DONE]");
                    if (current.contains("error"))
                        return reactor.core.publisher.Flux.just(content, usage)
                                .concatWith(
                                        reactor.core.publisher.Flux.error(
                                                new IllegalStateException(
                                                        "private-provider-failure")));
                    return reactor.core.publisher.Flux.just(content, usage, usage, end, "[DONE]");
                }

                public void close() {}
            };
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "Harness persists reply and provider usage under the caller budget")
    void harnessPersistsReplyAndProviderUsageUnderTheCallerBudget(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var admin = new Browser();
        admin.login("Initial-password-11");
        admin.csrf();
        admin.request(
                "POST",
                "/api/auth/password",
                "{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Model-password-14\"}",
                true);
        admin.login("Model-password-14");
        admin.csrf();
        var cid =
                json.readTree(admin.request("POST", "/api/conversations", "{}", true).body())
                        .get("id")
                        .asString();
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
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"1\"}", true);
        var submit =
                admin.request(
                        "POST",
                        "/api/conversations/" + cid + "/tasks",
                        json.writeValueAsString(
                                Map.of(
                                        "requestId",
                                        "model-one",
                                        "modelId",
                                        modelId,
                                        "message",
                                        "请完成文字任务")),
                        true);
        assertThat(submit.statusCode()).isEqualTo(202);
        var tid = json.readTree(submit.body()).get("id").asString();
        awaitStatus(admin, tid, "COMPLETED");
        assertThat(
                        admin.request("GET", "/api/conversations/" + cid + "/messages", null, false)
                                .body())
                .contains("模型完成");
        var calls = admin.request("GET", "/api/tasks/" + tid + "/calls", null, false);
        assertThat(calls.statusCode()).isEqualTo(200);
        assertThat(calls.body())
                .contains("PROVIDER", "provider-one", "0.000140")
                .doesNotContain("synthetic-model-secret");
        var budget = admin.request("GET", "/api/budget", null, false);
        assertThat(json.readTree(budget.body()).get("spent").asString()).isEqualTo("0.000140");
        assertThat(json.readTree(budget.body()).get("reserved").asString()).isEqualTo("0.000000");
        var stored = submit(admin, cid, modelId, "storage");
        awaitOpenCall(admin, stored);
        var replyPath = "/api/tasks/" + stored + "/reply-stream";
        assertThat(new Browser().request("GET", replyPath, null, false).statusCode())
                .isEqualTo(401);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var streamRequest =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + replyPath))
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build();
            for (int connection = 0; connection < 2; connection++) {
                var snapshot =
                        pool.submit(
                                () -> {
                                    var response =
                                            admin.client.send(
                                                    streamRequest,
                                                    HttpResponse.BodyHandlers.ofLines());
                                    assertThat(response.statusCode()).isEqualTo(200);
                                    try (var lines = response.body()) {
                                        return lines.filter(line -> line.contains("模型完成"))
                                                .findFirst()
                                                .orElseThrow();
                                    }
                                });
                assertThat(snapshot.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .doesNotContain("private-reasoning-fixture");
            }
            assertThat(admin.request("GET", "/api/tasks/" + stored, null, false).body())
                    .contains("RUNNING");
            try (var pause = MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId())) {
                pause.exec();
            }
            java.util.concurrent.Future<?> observed;
            try {
                observed =
                        pool.submit(
                                () ->
                                        storageStream.tryEmitNext(
                                                "{\"id\":\"provider-storage\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}"));
                Thread.sleep(5000);
            } finally {
                try (var unpause =
                        MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId())) {
                    unpause.exec();
                }
            }
            observed.get();
        }
        storageStream.tryEmitNext(
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        storageStream.tryEmitNext("[DONE]");
        storageStream.tryEmitComplete();
        awaitStatus(admin, stored, "COMPLETED");
        var savedMessages =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/conversations/" + cid + "/messages",
                                                null,
                                                false)
                                        .body())
                        .get("items");
        var savedReplyCount = 0;
        for (var message : savedMessages) {
            if (message.path("taskId").asString().equals(stored)
                    && message.path("role").asString().equals("assistant")) {
                assertThat(message.path("content").asString()).isEqualTo("模型完成");
                savedReplyCount++;
            }
        }
        assertThat(savedReplyCount).isEqualTo(1);
        assertThat(admin.request("GET", replyPath, null, false).body())
                .contains("event:done")
                .doesNotContain("模型完成");
        var storedCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + stored + "/calls", null, false)
                                        .body())
                        .get(0);
        assertThat(storedCall.get("source").asString()).isEqualTo("PROVIDER");
        assertThat(storedCall.get("charged").asString()).isEqualTo("0.000140");
        var zero = submit(admin, cid, modelId, "zero-usage");
        awaitStatus(admin, zero, "COMPLETED");
        var zeroCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + zero + "/calls", null, false)
                                        .body())
                        .get(0);
        assertThat(zeroCall.get("inputTokens").asLong()).isZero();
        assertThat(zeroCall.get("outputTokens").asLong()).isZero();
        assertThat(zeroCall.get("charged").asString()).isEqualTo("0.000000");
        assertThat(zeroCall.get("source").asString()).isEqualTo("PROVIDER");
        var auxiliary = submit(admin, cid, modelId, "auxiliary");
        awaitStatus(admin, auxiliary, "COMPLETED");
        var auxiliaryCalls =
                json.readTree(
                        admin.request("GET", "/api/tasks/" + auxiliary + "/calls", null, false)
                                .body());
        assertThat(auxiliaryCalls.size()).isEqualTo(3);
        assertThat(auxiliaryCalls.toString()).contains("MAIN", "SUMMARY", "OVERFLOW");
        var missing = submit(admin, cid, modelId, "missing");
        awaitStatus(admin, missing, "COMPLETED");
        var missingCall =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + missing + "/calls",
                                                null,
                                                false)
                                        .body())
                        .get(0);
        assertThat(missingCall.get("source").asString()).isEqualTo("OBSERVED_ESTIMATE");
        assertThat(missingCall.get("inputTokens").isNull()).isTrue();
        assertThat(missingCall.get("outputTokens").isNull()).isTrue();
        var before =
                new java.math.BigDecimal(
                        json.readTree(admin.request("GET", "/api/budget", null, false).body())
                                .get("spent")
                                .asString());
        var estimated = new java.math.BigDecimal(missingCall.get("charged").asString());
        ledger.observe(missingCall.get("id").asString(), 0, 100L, 20L, "late-provider-id");
        ledger.observe(missingCall.get("id").asString(), 0, 100L, 20L, "late-provider-id");
        ledger.complete(missingCall.get("id").asString());
        ledger.complete(missingCall.get("id").asString());
        var lateCall =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + missing + "/calls",
                                                null,
                                                false)
                                        .body())
                        .get(0);
        assertThat(lateCall.get("source").asString()).isEqualTo("PROVIDER");
        assertThat(lateCall.get("adjustments").size()).isEqualTo(2);
        var after =
                new java.math.BigDecimal(
                        json.readTree(admin.request("GET", "/api/budget", null, false).body())
                                .get("spent")
                                .asString());
        assertThat(after)
                .isEqualByComparingTo(
                        before.subtract(estimated).add(new java.math.BigDecimal("0.000140")));
        var partial = submit(admin, cid, modelId, "partial");
        awaitStatus(admin, partial, "COMPLETED");
        var partialCall =
                json.readTree(
                                admin.request(
                                                "GET",
                                                "/api/tasks/" + partial + "/calls",
                                                null,
                                                false)
                                        .body())
                        .get(0);
        assertThat(partialCall.get("inputTokens").asLong()).isEqualTo(100);
        assertThat(partialCall.get("outputTokens").isNull()).isTrue();
        int count = requests.get();
        var failed = submit(admin, cid, modelId, "error");
        awaitStatus(admin, failed, "FAILED");
        assertThat(requests.get()).isEqualTo(count + 1);
        assertThat(admin.request("GET", "/api/tasks/" + failed + "/calls", null, false).body())
                .contains("PARTIAL_ESTIMATE")
                .doesNotContain("private-provider-failure");
        var diagnostic = admin.request("GET", "/api/tasks/" + failed + "/diagnostics", null, false);
        assertThat(diagnostic.statusCode()).isEqualTo(200);
        var diagnosticCall = json.readTree(diagnostic.body()).path("calls").get(0);
        var authoritativeCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + failed + "/calls", null, false)
                                        .body())
                        .get(0);
        assertThat(diagnosticCall.path("charged").isString()).isTrue();
        assertThat(diagnosticCall.path("charged").asString())
                .isEqualTo(authoritativeCall.path("charged").asString());
        assertThat(diagnosticCall.path("reservation").isString()).isTrue();
        assertThat(diagnosticCall.path("reservation").asString())
                .isEqualTo(authoritativeCall.path("reservation").asString());
        assertThat(diagnostic.body())
                .contains("model_failed", "PARTIAL_ESTIMATE", "UNKNOWN")
                .doesNotContain(
                        "private-provider-failure",
                        "synthetic-model-secret-12",
                        "credentialRef",
                        "baseUrl");
        assertThat(
                        admin.request(
                                        "GET",
                                        "/api/admin/tasks/" + auxiliary + "/diagnostics",
                                        null,
                                        false)
                                .body())
                .contains("MAIN", "SUMMARY", "OVERFLOW");
        assertThat(output.getAll())
                .doesNotContain("private-provider-failure", "synthetic-model-secret-12");
        var silent = submit(admin, cid, modelId, "silent");
        awaitStatus(admin, silent, "FAILED");
        var silentCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + silent + "/calls", null, false)
                                        .body())
                        .get(0);
        assertThat(silentCall.get("source").asString()).isEqualTo("LIMIT_ESTIMATE");
        assertThat(silentCall.get("charged").asString())
                .isEqualTo(silentCall.get("reservation").asString());
        count = requests.get();
        var capped = submit(admin, cid, modelId, "attempt-limit");
        awaitStatus(admin, capped, "FAILED");
        assertThat(requests.get()).isEqualTo(count + 4);
        assertThat(admin.request("GET", "/api/tasks/" + capped, null, false).body())
                .contains("model_attempt_limit");
        var guardConversation =
                json.readTree(admin.request("POST", "/api/conversations", "{}", true).body())
                        .get("id")
                        .asString();
        var guard = submit(admin, guardConversation, modelId, "hold guard");
        awaitOpenCall(admin, guard);
        var racing = submit(admin, cid, modelId, "hold race");
        awaitOpenCall(admin, racing);
        var racingCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + racing + "/calls", null, false)
                                        .body())
                        .get(0);
        var guardCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + guard + "/calls", null, false)
                                        .body())
                        .get(0);
        var previousSpent =
                new java.math.BigDecimal(
                        json.readTree(admin.request("GET", "/api/budget", null, false).body())
                                .get("spent")
                                .asString());
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    ledger.finish(racingCall.get("id").asString());
                                    return null;
                                }));
            start.countDown();
            for (var future : futures) future.get();
        }
        var settledCall =
                json.readTree(
                                admin.request("GET", "/api/tasks/" + racing + "/calls", null, false)
                                        .body())
                        .get(0);
        var settledBudget = json.readTree(admin.request("GET", "/api/budget", null, false).body());
        assertThat(new java.math.BigDecimal(settledBudget.get("spent").asString()))
                .isEqualByComparingTo(
                        previousSpent.add(
                                new java.math.BigDecimal(settledCall.get("charged").asString())));
        assertThat(settledBudget.get("reserved").asString())
                .isEqualTo(guardCall.get("reservation").asString());
        assertThat(settledCall.get("adjustments").size()).isEqualTo(1);
        admin.request("POST", "/api/tasks/" + racing + "/cancel", "{}", true);
        awaitStatus(admin, racing, "CANCELLED");
        admin.request("POST", "/api/tasks/" + guard + "/cancel", "{}", true);
        awaitStatus(admin, guard, "CANCELLED");
        var held = submit(admin, cid, modelId, "hold");
        awaitOpenCall(admin, held);
        var heldBudget = json.readTree(admin.request("GET", "/api/budget", null, false).body());
        var exactLimit =
                new java.math.BigDecimal(heldBudget.get("spent").asString())
                        .add(new java.math.BigDecimal(heldBudget.get("reserved").asString()));
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/budget",
                json.writeValueAsString(Map.of("limit", exactLimit.toPlainString())),
                true);
        var cid2 =
                json.readTree(admin.request("POST", "/api/conversations", "{}", true).body())
                        .get("id")
                        .asString();
        var noBudget = submit(admin, cid2, modelId, "another");
        awaitStatus(admin, noBudget, "FAILED");
        assertThat(admin.request("GET", "/api/tasks/" + noBudget, null, false).body())
                .contains("budget_exhausted");
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "GET",
                                                        "/api/tasks/" + noBudget + "/calls",
                                                        null,
                                                        false)
                                                .body())
                                .size())
                .isZero();
        admin.request("POST", "/api/tasks/" + held + "/cancel", "{}", true);
        awaitStatus(admin, held, "CANCELLED");
        assertThat(cancellations.get()).isGreaterThan(0);
        var cancelledInput =
                new io.iwork.conversation.TaskRunner.Input(
                        held,
                        cid,
                        identities.authenticate("admin", "Model-password-14"),
                        modelId,
                        "summary after cancellation");
        var summaryMessage =
                io.agentscope.core.message.Msg.builder()
                        .name("user")
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .textContent("摘要")
                        .build();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                models.forTask(cancelledInput, "SUMMARY").stream(
                                                List.of(summaryMessage), List.of(), null)
                                        .blockLast())
                .hasMessageContaining("task_not_running");

        assertThat(
                        json.readTree(admin.request("GET", "/api/budget", null, false).body())
                                .get("reserved")
                                .asString())
                .isEqualTo("0.000000");
        admin.request("PUT", "/api/admin/users/" + uid + "/budget", "{\"limit\":\"1\"}", true);
        auxiliaryGate = new java.util.concurrent.CountDownLatch(1);
        var revoked = submit(admin, cid, modelId, "auxiliary revoked");
        awaitClosedCall(admin, revoked);
        admin.request(
                "PUT", "/api/admin/users/" + uid + "/model-access", "{\"modelIds\":[]}", true);
        auxiliaryGate.countDown();
        awaitStatus(admin, revoked, "FAILED");
        assertThat(admin.request("GET", "/api/tasks/" + revoked, null, false).body())
                .contains("model_not_authorized");
        assertThat(
                        json.readTree(
                                        admin.request(
                                                        "GET",
                                                        "/api/tasks/" + revoked + "/calls",
                                                        null,
                                                        false)
                                                .body())
                                .size())
                .isEqualTo(1);
        admin.request(
                "PUT",
                "/api/admin/users/" + uid + "/model-access",
                json.writeValueAsString(Map.of("modelIds", List.of(modelId))),
                true);
        var skill =
                java.nio.file.Path.of(
                        "target/model-execution-files/harness/skills/unexpected/SKILL.md");
        java.nio.file.Files.createDirectories(skill.getParent());
        java.nio.file.Files.writeString(
                skill,
                "---\nname: unexpected\ndescription: forbidden skill fixture\n---\nNever execute this skill");
        count = requests.get();
        try {
            var unexpected = submit(admin, cid, modelId, "unexpected workspace skill");
            awaitStatus(admin, unexpected, "FAILED");
            assertThat(requests.get()).isEqualTo(count);
        } finally {
            java.nio.file.Files.deleteIfExists(skill);
            java.nio.file.Files.deleteIfExists(skill.getParent());
            java.nio.file.Files.deleteIfExists(skill.getParent().getParent());
        }
    }

    String submit(Browser browser, String conversation, String model, String message)
            throws Exception {
        var response =
                browser.request(
                        "POST",
                        "/api/conversations/" + conversation + "/tasks",
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
        return json.readTree(response.body()).get("id").asString();
    }

    void awaitStatus(Browser browser, String id, String expected) throws Exception {
        String body = "";
        for (int i = 0; i < 150; i++) {
            body = browser.request("GET", "/api/tasks/" + id, null, false).body();
            var task = json.readTree(body);
            if (!task.get("status").asString().equals("RUNNING")
                    && task.get("executionState").asString().equals("STOPPED")) break;
            Thread.sleep(100);
        }
        assertThat(json.readTree(body).get("status").asString()).isEqualTo(expected);
        assertThat(body).contains("STOPPED");
    }

    void awaitOpenCall(Browser browser, String id) throws Exception {
        awaitCall(browser, id, "OPEN");
    }

    void awaitClosedCall(Browser browser, String id) throws Exception {
        awaitCall(browser, id, "CLOSED");
    }

    void awaitCall(Browser browser, String id, String status) throws Exception {
        String body = "";
        for (int i = 0; i < 150; i++) {
            body = browser.request("GET", "/api/tasks/" + id + "/calls", null, false).body();
            if (body.contains(status) && body.contains("provider-one")) return;
            Thread.sleep(100);
        }
        assertThat(body).contains(status, "provider-one");
    }
}
