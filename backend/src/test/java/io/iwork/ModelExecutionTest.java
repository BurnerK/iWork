package io.iwork;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "iwork.files-directory=target/model-execution-files", "iwork.cookie-secure=false",
    "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12", "iwork.bootstrap.username=admin", "iwork.bootstrap.password=Initial-password-11"})
class ModelExecutionTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7").withUrlParam("connectTimeout","3000").withUrlParam("socketTimeout","3000");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
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
        HttpResponse<String> request(String method, String path, String body, boolean token) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(java.time.Duration.ofSeconds(15));
            if(token && csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if(body != null) builder.header("Content-Type", path.endsWith("/login") ? "application/x-www-form-urlencoded" : "application/json");
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception { csrf = json.readTree(request("GET", "/api/auth/csrf", null, false).body()).get("token").asText(); }
        HttpResponse<String> login(String username, String password) throws Exception {
            csrf(); return request("POST", "/api/auth/login", "username=" + username + "&password=" + password, true);
        }
    }
    static final java.util.concurrent.atomic.AtomicInteger cancellations=new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    static final reactor.core.publisher.Sinks.Many<String> storageStream=reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
    static volatile java.util.concurrent.CountDownLatch auxiliaryGate=new java.util.concurrent.CountDownLatch(0);
    @org.springframework.beans.factory.annotation.Autowired io.iwork.model.ModelCallLedger ledger;
    @org.springframework.beans.factory.annotation.Autowired io.iwork.model.GovernedModel models;
    @org.springframework.beans.factory.annotation.Autowired io.iwork.identity.IdentityService identities;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        io.iwork.conversation.TaskRunner composedRunner(io.iwork.agent.HarnessTaskRunner runner,io.iwork.model.GovernedModel models) { return input -> {
            var reply=runner.execute(input);
            if(input.message().contains("auxiliary")) {
                auxiliaryGate.await();
                var message=io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER).textContent("摘要").build();
                models.forTask(input,"SUMMARY").stream(List.of(message),List.of(),null).blockLast();
                models.forTask(input,"OVERFLOW").stream(List.of(message),List.of(),null).blockLast();
            }
            if(input.message().contains("attempt-limit")) {
                var message=io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER).textContent("摘要").build();
                for(int i=0;i<4;i++) models.forTask(input,"SUMMARY").stream(List.of(message),List.of(),null).blockLast();
            }
            return reply;
        }; }
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport providerFixture() { return new io.agentscope.core.model.transport.HttpTransport() {
            public io.agentscope.core.model.transport.HttpResponse execute(io.agentscope.core.model.transport.HttpRequest request) { throw new AssertionError("stream only"); }
            public reactor.core.publisher.Flux<String> stream(io.agentscope.core.model.transport.HttpRequest request) {
                requests.incrementAndGet();
                assertThat(request.getBody()).doesNotContain("\"tools\"", "shell", "read_file", "web_search", "subagent", "plan_enter").contains("2048","include_usage");
                String current="";
                for(var message:new ObjectMapper().readTree(request.getBody()).path("messages"))
                    if(message.path("role").asText().equals("user")) current=message.path("content").asText();
                String content="{\"id\":\"provider-one\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"模型完成\"},\"finish_reason\":null}]}";
                String usage="{\"id\":\"provider-one\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}";
                String end="{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
                if(current.contains("storage")) return reactor.core.publisher.Flux.just(content).concatWith(storageStream.asFlux());
                if(current.contains("zero-usage")) return reactor.core.publisher.Flux.just(content,"{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}",end,"[DONE]");
                if(current.contains("silent")) return reactor.core.publisher.Flux.error(new IllegalStateException("no observed output"));
                if(current.contains("hold-known")) return reactor.core.publisher.Flux.just(content,usage).concatWith(reactor.core.publisher.Flux.never()).doOnCancel(cancellations::incrementAndGet);
                if(current.contains("hold")) return reactor.core.publisher.Flux.just(content).concatWith(reactor.core.publisher.Flux.never()).doOnCancel(cancellations::incrementAndGet);
                if(current.contains("missing")) return reactor.core.publisher.Flux.just(content,end,"[DONE]");
                if(current.contains("partial")) return reactor.core.publisher.Flux.just(content,"{\"choices\":[],\"usage\":{\"prompt_tokens\":100}}",end,"[DONE]");
                if(current.contains("error")) return reactor.core.publisher.Flux.just(content,usage).concatWith(reactor.core.publisher.Flux.error(new IllegalStateException("private-provider-failure")));
                return reactor.core.publisher.Flux.just(content,usage,usage,end,"[DONE]");
            }
            public void close() {}
        }; }
    }
    @Test void harnessPersistsReplyAndProviderUsageUnderTheCallerBudget() throws Exception {
        var admin=new Browser(); admin.login("admin","Initial-password-11"); admin.csrf();
        admin.request("POST","/api/auth/password","{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Model-password-14\"}",true);
        admin.login("admin","Model-password-14"); admin.csrf();
        var cid=json.readTree(admin.request("POST","/api/conversations","{}",true).body()).get("id").asText();
        var model=admin.request("POST","/api/admin/models",json.writeValueAsString(Map.of("name","Fixture", "baseUrl","https://models.example.test/v1", "modelName","writer", "credentialRef","IWORK_MODEL_KEY_TEST", "inputPrice","1", "outputPrice","2", "enabled",true,"contextWindow",131072)),true);
        var modelId=json.readTree(model.body()).get("id").asText();
        var uid=json.readTree(admin.request("GET","/api/auth/me",null,false).body()).get("id").asText();
        admin.request("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(modelId))),true);
        admin.request("PUT","/api/admin/users/"+uid+"/budget","{\"limit\":\"1\"}",true);
        var submit=admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(Map.of("requestId","model-one","modelId",modelId,"message","请完成文字任务")),true);
        assertThat(submit.statusCode()).isEqualTo(202);
        var tid=json.readTree(submit.body()).get("id").asText();
        awaitStatus(admin,tid,"COMPLETED");
        assertThat(admin.request("GET","/api/conversations/"+cid+"/messages",null,false).body()).contains("模型完成");
        var calls=admin.request("GET","/api/tasks/"+tid+"/calls",null,false);
        assertThat(calls.statusCode()).isEqualTo(200);
        assertThat(calls.body()).contains("PROVIDER", "provider-one", "0.000140").doesNotContain("synthetic-model-secret");
        var budget=admin.request("GET","/api/budget",null,false);
        assertThat(json.readTree(budget.body()).get("spent").asText()).isEqualTo("0.000140");
        assertThat(json.readTree(budget.body()).get("reserved").asText()).isEqualTo("0.000000");
        var stored=submit(admin,cid,modelId,"storage"); awaitOpenCall(admin,stored);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
            java.util.concurrent.Future<?> observed;
            try {
                observed=pool.submit(() -> storageStream.tryEmitNext("{\"id\":\"provider-storage\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}"));
                Thread.sleep(5000);
            } finally { MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec(); }
            observed.get();
        }
        storageStream.tryEmitNext("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        storageStream.tryEmitNext("[DONE]"); storageStream.tryEmitComplete();
        awaitStatus(admin,stored,"COMPLETED");
        var storedCall=json.readTree(admin.request("GET","/api/tasks/"+stored+"/calls",null,false).body()).get(0);
        assertThat(storedCall.get("source").asText()).isEqualTo("PROVIDER"); assertThat(storedCall.get("charged").asText()).isEqualTo("0.000140");
        var zero=submit(admin,cid,modelId,"zero-usage"); awaitStatus(admin,zero,"COMPLETED");
        var zeroCall=json.readTree(admin.request("GET","/api/tasks/"+zero+"/calls",null,false).body()).get(0);
        assertThat(zeroCall.get("inputTokens").asLong()).isZero(); assertThat(zeroCall.get("outputTokens").asLong()).isZero();
        assertThat(zeroCall.get("charged").asText()).isEqualTo("0.000000"); assertThat(zeroCall.get("source").asText()).isEqualTo("PROVIDER");
        var auxiliary=submit(admin,cid,modelId,"auxiliary"); awaitStatus(admin,auxiliary,"COMPLETED");
        var auxiliaryCalls=json.readTree(admin.request("GET","/api/tasks/"+auxiliary+"/calls",null,false).body());
        assertThat(auxiliaryCalls.size()).isEqualTo(3);
        assertThat(auxiliaryCalls.toString()).contains("MAIN","SUMMARY","OVERFLOW");
        var missing=submit(admin,cid,modelId,"missing"); awaitStatus(admin,missing,"COMPLETED");
        var missingCall=json.readTree(admin.request("GET","/api/tasks/"+missing+"/calls",null,false).body()).get(0);
        assertThat(missingCall.get("source").asText()).isEqualTo("OBSERVED_ESTIMATE");
        assertThat(missingCall.get("inputTokens").isNull()).isTrue(); assertThat(missingCall.get("outputTokens").isNull()).isTrue();
        var before=new java.math.BigDecimal(json.readTree(admin.request("GET","/api/budget",null,false).body()).get("spent").asText());
        var estimated=new java.math.BigDecimal(missingCall.get("charged").asText());
        ledger.observe(missingCall.get("id").asText(),0,100L,20L,"late-provider-id");
        ledger.observe(missingCall.get("id").asText(),0,100L,20L,"late-provider-id");
        ledger.complete(missingCall.get("id").asText());
        ledger.complete(missingCall.get("id").asText());
        var lateCall=json.readTree(admin.request("GET","/api/tasks/"+missing+"/calls",null,false).body()).get(0);
        assertThat(lateCall.get("source").asText()).isEqualTo("PROVIDER");
        assertThat(lateCall.get("adjustments").size()).isEqualTo(2);
        var after=new java.math.BigDecimal(json.readTree(admin.request("GET","/api/budget",null,false).body()).get("spent").asText());
        assertThat(after).isEqualByComparingTo(before.subtract(estimated).add(new java.math.BigDecimal("0.000140")));
        var partial=submit(admin,cid,modelId,"partial"); awaitStatus(admin,partial,"COMPLETED");
        var partialCall=json.readTree(admin.request("GET","/api/tasks/"+partial+"/calls",null,false).body()).get(0);
        assertThat(partialCall.get("inputTokens").asLong()).isEqualTo(100);
        assertThat(partialCall.get("outputTokens").isNull()).isTrue();
        int count=requests.get();
        var failed=submit(admin,cid,modelId,"error"); awaitStatus(admin,failed,"FAILED");
        assertThat(requests.get()).isEqualTo(count+1);
        assertThat(admin.request("GET","/api/tasks/"+failed+"/calls",null,false).body()).contains("PARTIAL_ESTIMATE").doesNotContain("private-provider-failure");
        var silent=submit(admin,cid,modelId,"silent"); awaitStatus(admin,silent,"FAILED");
        var silentCall=json.readTree(admin.request("GET","/api/tasks/"+silent+"/calls",null,false).body()).get(0);
        assertThat(silentCall.get("source").asText()).isEqualTo("LIMIT_ESTIMATE");
        assertThat(silentCall.get("charged").asText()).isEqualTo(silentCall.get("reservation").asText());
        count=requests.get();
        var capped=submit(admin,cid,modelId,"attempt-limit"); awaitStatus(admin,capped,"FAILED");
        assertThat(requests.get()).isEqualTo(count+4);
        assertThat(admin.request("GET","/api/tasks/"+capped,null,false).body()).contains("model_attempt_limit");
        var guardConversation=json.readTree(admin.request("POST","/api/conversations","{}",true).body()).get("id").asText();
        var guard=submit(admin,guardConversation,modelId,"hold guard"); awaitOpenCall(admin,guard);
        var racing=submit(admin,cid,modelId,"hold race"); awaitOpenCall(admin,racing);
        var racingCall=json.readTree(admin.request("GET","/api/tasks/"+racing+"/calls",null,false).body()).get(0);
        var guardCall=json.readTree(admin.request("GET","/api/tasks/"+guard+"/calls",null,false).body()).get(0);
        var previousSpent=new java.math.BigDecimal(json.readTree(admin.request("GET","/api/budget",null,false).body()).get("spent").asText());
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1); var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<16;i++) futures.add(pool.submit(() -> { start.await(); ledger.finish(racingCall.get("id").asText()); return null; }));
            start.countDown(); for(var future:futures) future.get();
        }
        var settledCall=json.readTree(admin.request("GET","/api/tasks/"+racing+"/calls",null,false).body()).get(0);
        var settledBudget=json.readTree(admin.request("GET","/api/budget",null,false).body());
        assertThat(new java.math.BigDecimal(settledBudget.get("spent").asText())).isEqualByComparingTo(previousSpent.add(new java.math.BigDecimal(settledCall.get("charged").asText())));
        assertThat(settledBudget.get("reserved").asText()).isEqualTo(guardCall.get("reservation").asText());
        assertThat(settledCall.get("adjustments").size()).isEqualTo(1);
        admin.request("POST","/api/tasks/"+racing+"/cancel","{}",true); awaitStatus(admin,racing,"CANCELLED");
        admin.request("POST","/api/tasks/"+guard+"/cancel","{}",true); awaitStatus(admin,guard,"CANCELLED");
        var held=submit(admin,cid,modelId,"hold"); awaitOpenCall(admin,held);
        var heldBudget=json.readTree(admin.request("GET","/api/budget",null,false).body());
        var exactLimit=new java.math.BigDecimal(heldBudget.get("spent").asText()).add(new java.math.BigDecimal(heldBudget.get("reserved").asText()));
        admin.request("PUT","/api/admin/users/"+uid+"/budget",json.writeValueAsString(Map.of("limit",exactLimit.toPlainString())),true);
        var cid2=json.readTree(admin.request("POST","/api/conversations","{}",true).body()).get("id").asText();
        var noBudget=submit(admin,cid2,modelId,"another"); awaitStatus(admin,noBudget,"FAILED");
        assertThat(admin.request("GET","/api/tasks/"+noBudget,null,false).body()).contains("budget_exhausted");
        assertThat(json.readTree(admin.request("GET","/api/tasks/"+noBudget+"/calls",null,false).body()).size()).isZero();
        admin.request("POST","/api/tasks/"+held+"/cancel","{}",true); awaitStatus(admin,held,"CANCELLED");
        assertThat(cancellations.get()).isGreaterThan(0);
        var cancelledInput=new io.iwork.conversation.TaskRunner.Input(held,cid,identities.authenticate("admin","Model-password-14"),modelId,"summary after cancellation");
        var summaryMessage=io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER).textContent("摘要").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> models.forTask(cancelledInput,"SUMMARY").stream(List.of(summaryMessage),List.of(),null).blockLast())
            .hasMessageContaining("task_not_running");

        assertThat(json.readTree(admin.request("GET","/api/budget",null,false).body()).get("reserved").asText()).isEqualTo("0.000000");
        admin.request("PUT","/api/admin/users/"+uid+"/budget","{\"limit\":\"1\"}",true);
        auxiliaryGate=new java.util.concurrent.CountDownLatch(1);
        var revoked=submit(admin,cid,modelId,"auxiliary revoked"); awaitClosedCall(admin,revoked);
        admin.request("PUT","/api/admin/users/"+uid+"/model-access","{\"modelIds\":[]}",true); auxiliaryGate.countDown();
        awaitStatus(admin,revoked,"FAILED");
        assertThat(admin.request("GET","/api/tasks/"+revoked,null,false).body()).contains("model_not_authorized");
        assertThat(json.readTree(admin.request("GET","/api/tasks/"+revoked+"/calls",null,false).body()).size()).isEqualTo(1);
        admin.request("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(modelId))),true);
        var skill=java.nio.file.Path.of("target/model-execution-files/harness/skills/unexpected/SKILL.md");
        java.nio.file.Files.createDirectories(skill.getParent());
        java.nio.file.Files.writeString(skill,"---\nname: unexpected\ndescription: forbidden skill fixture\n---\nNever execute this skill");
        count=requests.get();
        try {
            var unexpected=submit(admin,cid,modelId,"unexpected workspace skill"); awaitStatus(admin,unexpected,"FAILED");
            assertThat(requests.get()).isEqualTo(count);
        } finally {
            java.nio.file.Files.deleteIfExists(skill); java.nio.file.Files.deleteIfExists(skill.getParent()); java.nio.file.Files.deleteIfExists(skill.getParent().getParent());
        }


    }
    String submit(Browser browser,String conversation,String model,String message) throws Exception {
        var response=browser.request("POST","/api/conversations/"+conversation+"/tasks",json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"modelId",model,"message",message)),true);
        assertThat(response.statusCode()).isEqualTo(202); return json.readTree(response.body()).get("id").asText();
    }
    void awaitStatus(Browser browser,String id,String expected) throws Exception {
        String body="";
        for(int i=0;i<150;i++) {
            body=browser.request("GET","/api/tasks/"+id,null,false).body();
            var task=json.readTree(body);
            if(!task.get("status").asText().equals("RUNNING") && task.get("executionState").asText().equals("STOPPED")) break;
            Thread.sleep(100);
        }
        assertThat(json.readTree(body).get("status").asText()).isEqualTo(expected);
        assertThat(body).contains("STOPPED");
    }
    void awaitOpenCall(Browser browser,String id) throws Exception { awaitCall(browser,id,"OPEN"); }
    void awaitClosedCall(Browser browser,String id) throws Exception { awaitCall(browser,id,"CLOSED"); }
    void awaitCall(Browser browser,String id,String status) throws Exception {
        String body="";
        for(int i=0;i<150;i++) {
            body=browser.request("GET","/api/tasks/"+id+"/calls",null,false).body();
            if(body.contains(status) && body.contains("provider-one")) return;
            Thread.sleep(100);
        }
        assertThat(body).contains(status,"provider-one");
    }

}
