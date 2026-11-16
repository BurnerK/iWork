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
    "iwork.files-directory=target/personal-agent-files", "iwork.cookie-secure=false",
    "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12", "iwork.bootstrap.username=admin", "iwork.bootstrap.password=Initial-password-11"})
public class PersonalAgentTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7").withUrlParam("connectTimeout","3000").withUrlParam("socketTimeout","3000");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iwork.sandbox-url", () -> System.getenv().getOrDefault("IWORK_SANDBOX_TEST_URL",""));
        r.add("iwork.sandbox-key-file", () -> System.getenv().getOrDefault("IWORK_SANDBOX_TEST_KEY_FILE",""));
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
    @Test void personalConfigurationIsOwnedValidatedAndReusable() throws Exception {
        var admin=new Browser(); admin.login("admin","Initial-password-11"); admin.csrf();
        admin.request("POST","/api/auth/password",json.writeValueAsString(Map.of("currentPassword","Initial-password-11","newPassword","Agent-password-22")),true);
        admin.login("admin","Agent-password-22"); admin.csrf();
        String model=json.readTree(admin.request("POST","/api/admin/models",json.writeValueAsString(Map.of("name","Fixture","baseUrl","https://models.example.test/v1","modelName","writer","credentialRef","IWORK_MODEL_KEY_TEST","inputPrice","1","outputPrice","2","enabled",true,"contextWindow",16384)),true).body()).path("id").asText();
        String uid=json.readTree(admin.request("GET","/api/auth/me",null,false).body()).path("id").asText();
        admin.request("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(model))),true);
        var input=Map.of("name","项目写作助手","instructions","按行动清单组织正文。","modelId",model);
        var created=admin.request("POST","/api/agents",json.writeValueAsString(input),true);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("id").asText();
        assertThat(admin.request("GET","/api/agents/"+id,null,false).body()).contains("按行动清单组织正文。",model);
        assertThat(admin.request("GET","/api/agents",null,false).body()).contains(id);
        var update=Map.of("name","修改后的助手","instructions","按分工组织正文。","modelId",model);
        assertThat(admin.request("PUT","/api/agents/"+id,json.writeValueAsString(update),true).statusCode()).isEqualTo(200);
        assertThat(admin.request("GET","/api/agents/"+id,null,false).body()).contains("按分工组织正文。").doesNotContain("按行动清单");
        var invalid=new HashMap<String,Object>(input); invalid.put("tools",List.of("shell"));
        assertThat(admin.request("POST","/api/agents",json.writeValueAsString(invalid),true).statusCode()).isEqualTo(400);
        invalid=new HashMap<>(input); invalid.put("instructions","x".repeat(4001));
        assertThat(admin.request("POST","/api/agents",json.writeValueAsString(invalid),true).statusCode()).isEqualTo(400);
        var issued=json.readTree(admin.request("POST","/api/admin/users",json.writeValueAsString(Map.of("username","agent-other","displayName","Other")),true).body());
        var other=new Browser(); String temporary=issued.path("temporaryPassword").asText(); other.login("agent-other",temporary); other.csrf();
        other.request("POST","/api/auth/password",json.writeValueAsString(Map.of("currentPassword",temporary,"newPassword","Other-agent-password")),true);
        other.login("agent-other","Other-agent-password"); other.csrf();
        assertThat(other.request("GET","/api/agents/"+id,null,false).statusCode()).isEqualTo(404);
        assertThat(other.request("PUT","/api/agents/"+id,json.writeValueAsString(update),true).statusCode()).isEqualTo(404);
        assertThat(other.request("POST","/api/agents",json.writeValueAsString(input),true).statusCode()).isEqualTo(403);
        assertThat(other.request("GET","/api/agents",null,false).body()).doesNotContain(id);
        admin.request("PUT","/api/admin/users/"+uid+"/budget",json.writeValueAsString(Map.of("limit","5")),true);
        admin.request("PUT","/api/agents/"+id,json.writeValueAsString(input),true);
        String cid=json.readTree(admin.request("POST","/api/conversations",json.writeValueAsString(Map.of("title","智能体配置执行")),true).body()).path("id").asText();
        var upload=admin.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/materials")).header("X-CSRF-TOKEN",admin.csrf).header("X-File-Name","agent-source.md").header("Content-Type","application/octet-stream").POST(HttpRequest.BodyPublishers.ofString("蓝鲸项目，周五交付。")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(201);
        String material=json.readTree(upload.body()).path("id").asText();
        var taskBody=Map.of("requestId",UUID.randomUUID().toString(),"agentId",id,"message","hold","outputMode","DOCUMENT","materialIds",List.of(material));
        var accepted=admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(taskBody),true);
        assertThat(accepted.statusCode()).isEqualTo(202);
        String task=json.readTree(accepted.body()).path("id").asText();
        for(int i=0;i<200 && !entered.get();i++) Thread.sleep(25);
        assertThat(entered.get()).isTrue();
        admin.request("PUT","/api/agents/"+id,json.writeValueAsString(update),true);
        gate.tryEmitEmpty();
        await(admin,task,"COMPLETED");
        var first=json.readTree(admin.request("GET","/api/conversations/"+cid+"/documents",null,false).body()).get(0);
        assertThat(admin.request("GET","/api/document-versions/"+first.path("versionId").asText()+"/content",null,false).body()).contains("行动清单","蓝鲸项目").doesNotContain("分工组织");
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(taskBody),true).body()).contains(task);
        var next=new HashMap<String,Object>(taskBody); next.put("requestId",UUID.randomUUID().toString()); next.put("message","继续");
        String second=json.readTree(admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(next),true).body()).path("id").asText();
        await(admin,second,"COMPLETED");
        var latest=json.readTree(admin.request("GET","/api/conversations/"+cid+"/documents",null,false).body()).get(0);
        assertThat(admin.request("GET","/api/document-versions/"+latest.path("versionId").asText()+"/content",null,false).body()).contains("分工组织","蓝鲸项目");
        assertThat(other.request("GET","/api/tasks/"+task,null,false).statusCode()).isEqualTo(404);
        gate=reactor.core.publisher.Sinks.empty(); entered.set(false);
        next.put("requestId",UUID.randomUUID().toString()); next.put("message","revoke");
        String revoked=json.readTree(admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(next),true).body()).path("id").asText();
        for(int i=0;i<200 && !entered.get();i++) Thread.sleep(25);
        assertThat(entered.get()).isTrue();
        admin.request("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of())),true);
        gate.tryEmitEmpty(); await(admin,revoked,"FAILED");
        assertThat(admin.request("GET","/api/tasks/"+revoked,null,false).body()).contains("model_not_authorized");
        assertThat(json.readTree(admin.request("GET","/api/tasks/"+revoked+"/calls",null,false).body()).size()).isEqualTo(1);
        next.put("requestId",UUID.randomUUID().toString());
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(next),true).statusCode()).isEqualTo(403);
        String otherId=issued.path("user").path("id").asText();
        admin.request("PUT","/api/admin/users/"+otherId+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(model))),true);
        admin.request("PUT","/api/admin/users/"+otherId+"/budget",json.writeValueAsString(Map.of("limit","3")),true);
        String privateAgent=json.readTree(other.request("POST","/api/agents",json.writeValueAsString(input),true).body()).path("id").asText();
        assertThat(admin.request("GET","/api/agents/"+privateAgent,null,false).statusCode()).isEqualTo(404);
        assertThat(admin.request("PUT","/api/agents/"+privateAgent,json.writeValueAsString(update),true).statusCode()).isEqualTo(404);
        next.put("agentId",privateAgent); next.put("requestId",UUID.randomUUID().toString()); next.put("message","继续");
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(next),true).statusCode()).isEqualTo(404);
        String privateCid=json.readTree(other.request("POST","/api/conversations",json.writeValueAsString(Map.of("title","个人归属")),true).body()).path("id").asText();
        assertThat(other.request("POST","/api/conversations/"+privateCid+"/tasks",json.writeValueAsString(next),true).statusCode()).isEqualTo(404);
        var ownUpload=other.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/materials")).header("X-CSRF-TOKEN",other.csrf).header("X-File-Name","own.md").header("Content-Type","application/octet-stream").POST(HttpRequest.BodyPublishers.ofString("蓝鲸项目，周五交付。")).build(),HttpResponse.BodyHandlers.ofString());
        next.put("materialIds",List.of(json.readTree(ownUpload.body()).path("id").asText()));
        String budgetBefore=admin.request("GET","/api/budget",null,false).body();
        String ownTask=json.readTree(other.request("POST","/api/conversations/"+privateCid+"/tasks",json.writeValueAsString(next),true).body()).path("id").asText();
        await(other,ownTask,"COMPLETED");
        assertThat(admin.request("GET","/api/budget",null,false).body()).isEqualTo(budgetBefore);
        assertThat(new java.math.BigDecimal(json.readTree(other.request("GET","/api/budget",null,false).body()).path("spent").asText())).isPositive();
        assertThat(admin.request("GET","/api/tasks/"+ownTask+"/calls",null,false).statusCode()).isEqualTo(404);


    }
    void await(Browser browser,String task,String expected) throws Exception {
        for(int i=0;i<300;i++) {
            var state=json.readTree(browser.request("GET","/api/tasks/"+task,null,false).body());
            if(!state.path("status").asText().equals("RUNNING")) { assertThat(state.path("status").asText()).as(state.toString()).isEqualTo(expected); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not finish");
    }
    static final java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean();
    static volatile reactor.core.publisher.Sinks.Empty<Void> gate=reactor.core.publisher.Sinks.empty();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    public static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport providerFixture() { return new io.agentscope.core.model.transport.HttpTransport() {
            final ObjectMapper json=new ObjectMapper();
            public io.agentscope.core.model.transport.HttpResponse execute(io.agentscope.core.model.transport.HttpRequest request) { throw new AssertionError("stream only"); }
            public reactor.core.publisher.Flux<String> stream(io.agentscope.core.model.transport.HttpRequest request) {
                var body=json.readTree(request.getBody()); String system="",user=""; boolean read=false,prepared=false;
                for(var message:body.path("messages")) {
                    if(message.path("role").asText().equals("system")) system=message.path("content").asText();
                    if(message.path("role").asText().equals("user")) user=message.path("content").asText();
                    if(message.path("role").asText().equals("tool")) { if(message.path("content").asText().contains("PREPARED")) prepared=true; else { assertThat(message.path("content").asText()).contains("蓝鲸项目"); read=true; } }
                }
                var names=new HashSet<String>(); for(var tool:body.path("tools")) names.add(tool.path("function").path("name").asText());
                assertThat(names).contains("prepare_document").isSubsetOf("read_task_material","prepare_document","read_document_version");
                Map<String,Object> delta; String finish;
                if(prepared) { delta=Map.of("role","assistant","content","已按配置准备文档"); finish="stop"; }
                else {
                    String tool=read?"prepare_document":names.contains("read_document_version")?"read_document_version":"read_task_material";
                    var matcher=java.util.regex.Pattern.compile("id=([a-f0-9-]{36})").matcher(system);
                    assertThat(read || tool.equals("read_document_version") || matcher.find()).isTrue();
                    Map<String,Object> args=read?Map.of("title","智能体方案","markdown","# "+(system.contains("按分工组织正文")?"分工组织":"行动清单")+"\n蓝鲸项目，周五交付。\n"):tool.equals("read_document_version")?Map.of("offset",0):Map.of("materialId",matcher.group(1),"offset",0);
                    delta=Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",tool,"arguments",json.writeValueAsString(args))))); finish="tool_calls";
                }
                var response=reactor.core.publisher.Flux.just(json.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",finish)))),"[DONE]");
                if((user.equals("hold") || user.equals("revoke")) && !read && !prepared) { entered.set(true); return gate.asMono().thenMany(response); }
                return response;
            }
            public void close() {}
        }; }
    }
}
