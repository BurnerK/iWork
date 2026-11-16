package io.iwork;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
@EnabledIfEnvironmentVariable(named="IWORK_SANDBOX_TEST_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "iwork.files-directory=target/parsing-files","iwork.cookie-secure=false","IWORK_MODEL_KEY_TEST=synthetic-parse-key",
    "iwork.bootstrap.username=admin","iwork.bootstrap.password=Initial-password-16"})
class MaterialParsingTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost); r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("iwork.sandbox-url",()->System.getenv("IWORK_SANDBOX_TEST_URL")); r.add("iwork.sandbox-key-file",()->System.getenv("IWORK_SANDBOX_TEST_KEY_FILE"));
    }
    @LocalServerPort int port;
    final ObjectMapper json=new ObjectMapper();
    class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> send(String method,String path,String body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(java.time.Duration.ofSeconds(15));
            if(csrf!=null) builder.header("X-CSRF-TOKEN",csrf);
            builder.header("Content-Type",path.endsWith("/login")?"application/x-www-form-urlencoded":"application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        void login(String user,String pass) throws Exception {
            csrf=json.readTree(send("GET","/api/auth/csrf",null).body()).get("token").asText();
            assertThat(send("POST","/api/auth/login","username="+user+"&password="+pass).statusCode()).isEqualTo(200);
            csrf=json.readTree(send("GET","/api/auth/csrf",null).body()).get("token").asText();
        }
        String upload(byte[] bytes) throws Exception {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/materials")).header("X-CSRF-TOKEN",csrf)
                .header("Content-Type","application/octet-stream").header("X-File-Name","source.md").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201); return json.readTree(response.body()).get("id").asText();
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ProviderConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        io.agentscope.core.model.transport.HttpTransport parsingFixture() { return new io.agentscope.core.model.transport.HttpTransport() {
            final ObjectMapper json=new ObjectMapper();
            public io.agentscope.core.model.transport.HttpResponse execute(io.agentscope.core.model.transport.HttpRequest request) { throw new AssertionError(); }
            public reactor.core.publisher.Flux<String> stream(io.agentscope.core.model.transport.HttpRequest request) {
                var body=json.readTree(request.getBody());
                assertThat(body.get("tools").size()).isEqualTo(1);
                assertThat(body.get("tools").get(0).path("function").path("name").asText()).isEqualTo("read_task_material");
                boolean toolResult=false;
                for(var message:body.get("messages")) if(message.path("role").asText().equals("tool")) toolResult=true;
                String chunk;
                if(!toolResult) {
                    var matcher=java.util.regex.Pattern.compile("id=([a-f0-9-]{36})").matcher(request.getBody());
                    assertThat(matcher.find()).isTrue();
                    String id=matcher.group(1);
                    if(request.getBody().contains("foreign-reference")) id="00000000-0000-0000-0000-000000000000";
                    var arguments=json.writeValueAsString(Map.of("materialId",id,"offset",0));
                    var calls=new ArrayList<Map<String,Object>>();
                    calls.add(Map.of("index",0,"id","parse-one","type","function","function",Map.of("name","read_task_material","arguments",arguments)));
                    if(request.getBody().contains("duplicate-material")) calls.add(Map.of("index",1,"id","parse-two","type","function","function",Map.of("name","read_task_material","arguments",arguments)));
                    chunk=json.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","tool_calls",calls),"finish_reason","tool_calls"))));
                } else chunk="{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"资料处理已结束，请查看处理结果。\"},\"finish_reason\":\"stop\"}]}";
                return reactor.core.publisher.Flux.just(chunk,"{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}","[DONE]");
            }
            public void close() {}
        }; }
    }
    String task(Browser browser,String model,String material,String message) throws Exception {
        String cid=json.readTree(browser.send("POST","/api/conversations","{}").body()).get("id").asText();
        var response=browser.send("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(Map.of("modelId",model,"requestId",UUID.randomUUID().toString(),"message",message,"materialIds",List.of(material))));
        assertThat(response.statusCode()).isEqualTo(202); String id=json.readTree(response.body()).get("id").asText();
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<deadline) {
            var state=json.readTree(browser.send("GET","/api/tasks/"+id,null).body());
            if(!state.get("status").asText().equals("RUNNING")) { assertThat(state.get("status").asText()).isEqualTo("COMPLETED"); return id; }
            Thread.sleep(200);
        }
        throw new AssertionError("Task did not finish");
    }
    @Test void realHarnessParsesAuthorizedMaterialAndPublishesOnlyTaskMaterial() throws Exception {
        var admin=new Browser(); admin.login("admin","Initial-password-16");
        admin.send("POST","/api/auth/password","{\"currentPassword\":\"Initial-password-16\",\"newPassword\":\"Changed-password-16\"}"); admin.login("admin","Changed-password-16");
        String uid=json.readTree(admin.send("GET","/api/auth/me",null).body()).get("id").asText();
        String model=json.readTree(admin.send("POST","/api/admin/models",json.writeValueAsString(Map.of("name","Parser test","baseUrl","https://models.example.test/v1","modelName","writer","credentialRef","IWORK_MODEL_KEY_TEST","inputPrice","1","outputPrice","2","enabled",true))).body()).get("id").asText();
        admin.send("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(model))));
        admin.send("PUT","/api/admin/users/"+uid+"/budget","{\"limit\":\"1\"}");
        var material=admin.upload("\ufeff# 资料\r\n<script>不要执行</script>\r原文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var tid=task(admin,model,material,"请解析资料");
        var parses=json.readTree(admin.send("GET","/api/tasks/"+tid+"/parses",null).body());
        assertThat(parses.size()).isEqualTo(1); assertThat(parses.get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(parses.get(0).get("cleanup").asText()).isEqualTo("CONFIRMED");
        String path="/api/tasks/"+tid+"/parses/"+parses.get(0).get("id").asText();
        assertThat(json.readTree(admin.send("GET",path,null).body()).get("text").asText()).isEqualTo("# 资料\n<script>不要执行</script>\n原文");
        assertThat(admin.send("GET",path+"?offset=999999",null).statusCode()).isEqualTo(400);
        assertThat(admin.send("GET","/api/materials/"+material+"/content",null).body()).startsWith("\ufeff# 资料\r\n");
        var longMaterial=admin.upload(("a".repeat(11999)+"😀尾").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var longTask=task(admin,model,longMaterial,"请解析较长资料");
        var longParse=json.readTree(admin.send("GET","/api/tasks/"+longTask+"/parses",null).body()).get(0);
        String longPath="/api/tasks/"+longTask+"/parses/"+longParse.get("id").asText();
        var first=json.readTree(admin.send("GET",longPath,null).body());
        assertThat(first.get("text").asText()).hasSize(11999);
        assertThat(first.get("nextOffset").asInt()).isEqualTo(11999);
        assertThat(json.readTree(admin.send("GET",longPath+"?offset=11999",null).body()).get("text").asText()).isEqualTo("😀尾");
        assertThat(admin.send("GET",longPath+"?offset=12000",null).statusCode()).isEqualTo(400);
        var duplicateTask=task(admin,model,material,"duplicate-material");
        var duplicate=json.readTree(admin.send("GET","/api/tasks/"+duplicateTask+"/parses",null).body());
        assertThat(duplicate.size()).isEqualTo(1);
        assertThat(duplicate.get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(duplicate.get(0).get("characters").isNull()).isFalse();
        var invalid=admin.upload(new byte[]{(byte)0xff,(byte)0xfe,1});
        var failed=task(admin,model,invalid,"请解析资料");
        var failedParse=json.readTree(admin.send("GET","/api/tasks/"+failed+"/parses",null).body()).get(0);
        assertThat(failedParse.get("status").asText()).isEqualTo("FAILED"); assertThat(failedParse.get("cleanup").asText()).isEqualTo("CONFIRMED");
        var foreign=task(admin,model,material,"foreign-reference");
        assertThat(json.readTree(admin.send("GET","/api/tasks/"+foreign+"/parses",null).body()).isEmpty()).isTrue();
        var created=json.readTree(admin.send("POST","/api/admin/users","{\"username\":\"other-parser\",\"displayName\":\"Other\"}").body());
        var other=new Browser(); other.login("other-parser",created.get("temporaryPassword").asText());
        other.send("POST","/api/auth/password",json.writeValueAsString(Map.of("currentPassword",created.get("temporaryPassword").asText(),"newPassword","Other-password-16"))); other.login("other-parser","Other-password-16");
        assertThat(other.send("GET",path,null).statusCode()).isEqualTo(404);
        assertThat(other.send("GET","/api/tasks/"+tid+"/parses",null).statusCode()).isEqualTo(404);
    }
}
