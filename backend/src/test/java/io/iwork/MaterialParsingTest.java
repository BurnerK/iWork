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
    static final reactor.core.publisher.Sinks.Empty<Void> fileGate=reactor.core.publisher.Sinks.empty();
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
                var toolNames=new java.util.HashSet<String>();
                for(var tool:body.get("tools")) toolNames.add(tool.path("function").path("name").asText());
                boolean documentMode=toolNames.contains("prepare_document");
                assertThat(toolNames).isEqualTo(documentMode?Set.of("read_task_material","prepare_document"):Set.of("read_task_material"));
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
                } else if(documentMode && !request.getBody().contains("PREPARED") && !request.getBody().contains("skip-document")) {
                    assertThat(request.getBody()).contains("不要执行");
                    var arguments=json.writeValueAsString(Map.of("title","资料整理","markdown","# 资料整理\n\n原始资料包含标题与一段文字。\n\n<script>不要执行</script>\n"));
                    var calls=new ArrayList<Map<String,Object>>();
                    calls.add(Map.of("index",0,"id","write-one","type","function","function",Map.of("name","prepare_document","arguments",arguments)));
                    if(request.getBody().contains("duplicate-document")) calls.add(Map.of("index",1,"id","write-two","type","function","function",Map.of("name","prepare_document","arguments",arguments)));
                    chunk=json.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","tool_calls",calls),"finish_reason","tool_calls"))));
                } else chunk="{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"资料处理已结束，请查看处理结果。\"},\"finish_reason\":\"stop\"}]}";
                var answer=reactor.core.publisher.Flux.just(chunk,"{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
                if(documentMode && request.getBody().contains("PREPARED")) {
                    if(request.getBody().contains("hold-document")) return answer.concatWith(reactor.core.publisher.Flux.never());
                    if(request.getBody().contains("file-failure")) return answer.concatWith(fileGate.asMono().thenMany(reactor.core.publisher.Flux.just("[DONE]")));
                }
                return answer.concatWithValues("[DONE]");
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
    String submitDocument(Browser browser,String conversation,String model,String material,String message) throws Exception {
        var response=browser.send("POST","/api/conversations/"+conversation+"/tasks",json.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"modelId",model,"message",message,"materialIds",List.of(material),"outputMode","DOCUMENT")));
        assertThat(response.statusCode()).isEqualTo(202); return json.readTree(response.body()).get("id").asText();
    }
    void awaitTask(Browser browser,String id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) {
            var task=json.readTree(browser.send("GET","/api/tasks/"+id,null).body());
            if(task.get("status").asText().equals(expected) && task.get("executionState").asText().equals("STOPPED")) return;
            Thread.sleep(100);
        }
        throw new AssertionError(browser.send("GET","/api/tasks/"+id,null).body());
    }
    void awaitPreparedModelCall(Browser browser,String id) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline) {
            if(json.readTree(browser.send("GET","/api/tasks/"+id+"/calls",null).body()).size()==3) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Model did not receive the prepared document result");
    }
    @Test void realHarnessParsesAuthorizedMaterialAndPublishesOnlyTaskMaterial() throws Exception {
        var admin=new Browser(); admin.login("admin","Initial-password-16");
        admin.send("POST","/api/auth/password","{\"currentPassword\":\"Initial-password-16\",\"newPassword\":\"Changed-password-16\"}"); admin.login("admin","Changed-password-16");
        String uid=json.readTree(admin.send("GET","/api/auth/me",null).body()).get("id").asText();
        String model=json.readTree(admin.send("POST","/api/admin/models",json.writeValueAsString(Map.of("name","Parser test","baseUrl","https://models.example.test/v1","modelName","writer","credentialRef","IWORK_MODEL_KEY_TEST","inputPrice","1","outputPrice","2","enabled",true,"contextWindow",131072))).body()).get("id").asText();
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
        String documentConversation=json.readTree(admin.send("POST","/api/conversations","{}").body()).get("id").asText();
        String documentRequest=json.writeValueAsString(Map.of("requestId","document-one","modelId",model,"message","请把资料整理成正式文档 duplicate-document","materialIds",List.of(material),"outputMode","DOCUMENT"));
        var documentSubmit=admin.send("POST","/api/conversations/"+documentConversation+"/tasks",documentRequest);
        assertThat(documentSubmit.statusCode()).isEqualTo(202);
        var documentTask=json.readTree(documentSubmit.body()).get("id").asText();
        long documentDeadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<documentDeadline && json.readTree(admin.send("GET","/api/tasks/"+documentTask,null).body()).get("status").asText().equals("RUNNING")) Thread.sleep(100);
        assertThat(json.readTree(admin.send("GET","/api/tasks/"+documentTask,null).body()).get("status").asText()).isEqualTo("COMPLETED");
        var documents=json.readTree(admin.send("GET","/api/conversations/"+documentConversation+"/documents",null).body());
        assertThat(documents.size()).isEqualTo(1);
        String version=documents.get(0).get("versionId").asText();
        assertThat(version).isNotEqualTo(documentTask).isNotEqualTo(documents.get(0).get("id").asText());
        var download=admin.send("GET","/api/document-versions/"+version+"/content",null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("# 资料整理\n\n原始资料包含标题与一段文字。\n\n<script>不要执行</script>\n");
        assertThat(download.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        assertThat(admin.send("POST","/api/conversations/"+documentConversation+"/tasks",documentRequest).statusCode()).isEqualTo(202);
        assertThat(json.readTree(admin.send("GET","/api/conversations/"+documentConversation+"/documents",null).body()).size()).isEqualTo(1);
        assertThat(admin.send("POST","/api/conversations/"+documentConversation+"/tasks",documentRequest.replace("DOCUMENT","TEXT")).statusCode()).isEqualTo(409);
        assertThat(admin.send("POST","/api/tasks/"+documentTask+"/cancel","{}").body()).contains("COMPLETED");
        assertThat(admin.send("GET","/api/document-versions/"+version+"/content",null).body()).isEqualTo(download.body());
        var savedFile=java.nio.file.Path.of("target/parsing-files/documents",version+".md");
        var savedBytes=java.nio.file.Files.readAllBytes(savedFile);
        try {
            java.nio.file.Files.writeString(savedFile,"corrupted fixture");
            assertThat(admin.send("GET","/api/document-versions/"+version+"/content",null).statusCode()).isEqualTo(503);
        } finally { java.nio.file.Files.write(savedFile,savedBytes); }
        String failureConversation=json.readTree(admin.send("POST","/api/conversations","{}").body()).get("id").asText();
        String missing=submitDocument(admin,failureConversation,model,material,"skip-document");
        awaitTask(admin,missing,"FAILED");
        assertThat(admin.send("GET","/api/tasks/"+missing,null).body()).contains("document_not_generated");
        String cancelled=submitDocument(admin,failureConversation,model,material,"hold-document");
        awaitPreparedModelCall(admin,cancelled);
        assertThat(admin.send("POST","/api/tasks/"+cancelled+"/cancel","{}").statusCode()).isEqualTo(200);
        awaitTask(admin,cancelled,"CANCELLED");
        assertThat(json.readTree(admin.send("GET","/api/conversations/"+failureConversation+"/documents",null).body()).isEmpty()).isTrue();
        String storage=submitDocument(admin,failureConversation,model,material,"file-failure");
        awaitPreparedModelCall(admin,storage);
        var directory=java.nio.file.Path.of("target/parsing-files/documents");
        var held=directory.resolveSibling("documents-held-"+UUID.randomUUID());
        java.nio.file.Files.move(directory,held);
        try {
            java.nio.file.Files.writeString(directory,"unavailable directory fixture");
            fileGate.tryEmitEmpty(); awaitTask(admin,storage,"FAILED");
            assertThat(admin.send("GET","/api/tasks/"+storage,null).body()).contains("document_storage_unavailable");
        } finally { java.nio.file.Files.deleteIfExists(directory); java.nio.file.Files.move(held,directory); }
        assertThat(admin.send("GET","/api/document-versions/"+version+"/content",null).body()).isEqualTo(download.body());
        try(var connection=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE document_versions RENAME TO document_versions_unavailable");
            try {
                String rejected=submitDocument(admin,failureConversation,model,material,"数据库提交失败验收");
                awaitTask(admin,rejected,"FAILED");
            } finally { statement.execute("ALTER TABLE document_versions_unavailable RENAME TO document_versions"); }
            assertThat(json.readTree(admin.send("GET","/api/conversations/"+failureConversation+"/documents",null).body()).isEmpty()).isTrue();
        }
        assertThat(admin.send("GET","/api/document-versions/"+version+"/content",null).body()).isEqualTo(download.body());
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
        assertThat(other.send("GET","/api/document-versions/"+version+"/content",null).statusCode()).isEqualTo(404);
        assertThat(other.send("GET","/api/conversations/"+documentConversation+"/documents",null).statusCode()).isEqualTo(404);
        assertThat(other.send("GET","/api/tasks/"+tid+"/parses",null).statusCode()).isEqualTo(404);
    }
}
