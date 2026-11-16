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
    "iwork.files-directory=target/task-files", "iwork.cookie-secure=false",
    "IWORK_MODEL_KEY_TEST=synthetic-model-secret-12", "iwork.bootstrap.username=admin", "iwork.bootstrap.password=Initial-password-11"})
class TaskLifecycleTest {
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
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ExecutionConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        io.iwork.conversation.TaskRunner runner() { return input -> {
            if(input.message().equals("hold")) Thread.sleep(30000);
            if(input.message().equals("fail")) throw new IllegalStateException("private-provider-detail");
            if(input.message().equals("late")) {
                long until=System.nanoTime()+java.time.Duration.ofMillis(500).toNanos();
                while(System.nanoTime()<until) { try { Thread.sleep(20); } catch(InterruptedException ignored) {} }
            }
            return "Fixture reply";
        }; }
    }
    @Test void taskSubmissionIsPersistentAndIdempotent() throws Exception {
        var admin=new Browser(); admin.login("admin","Initial-password-11"); admin.csrf();
        admin.request("POST","/api/auth/password","{\"currentPassword\":\"Initial-password-11\",\"newPassword\":\"Changed-task-password\"}",true);
        admin.login("admin","Changed-task-password"); admin.csrf();
        var conversation=admin.request("POST","/api/conversations","{}",true);
        assertThat(conversation.statusCode()).isEqualTo(201);
        var cid=json.readTree(conversation.body()).get("id").asText();
        var model=admin.request("POST","/api/admin/models",json.writeValueAsString(Map.of("name","Fixture", "baseUrl","https://models.example.test/v1", "modelName","writer", "credentialRef","IWORK_MODEL_KEY_TEST", "inputPrice","1", "outputPrice","2", "enabled",true)),true);
        assertThat(model.statusCode()).isEqualTo(201);
        var modelId=json.readTree(model.body()).get("id").asText();
        var uid=json.readTree(admin.request("GET","/api/auth/me",null,false).body()).get("id").asText();
        admin.request("PUT","/api/admin/users/"+uid+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(modelId))),true);
        var uploaded=admin.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/materials"))
            .header("X-CSRF-TOKEN",admin.csrf).header("X-File-Name","source.txt").header("Content-Type","application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofString("source material")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        var materialId=json.readTree(uploaded.body()).get("id").asText();
        String body=json.writeValueAsString(Map.of("requestId","request-one","modelId",modelId,"message","hold","materialIds",List.of(materialId)));
        var response=admin.request("POST","/api/conversations/"+cid+"/tasks",body,true);
        assertThat(response.statusCode()).isEqualTo(202);
        var taskId=json.readTree(response.body()).get("id").asText();
        assertThat(admin.request("GET","/api/tasks/"+taskId+"/materials",null,false).body()).contains(materialId,"source.txt");
        var changedMaterials=json.writeValueAsString(Map.of("requestId","request-one","modelId",modelId,"message","hold","materialIds",List.of()));
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",changedMaterials,true).statusCode()).isEqualTo(409);

        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",body,true).body()).contains(taskId);
        assertThat(admin.request("GET","/api/tasks/"+taskId,null,false).body()).contains("RUNNING");
        assertThat(admin.request("GET","/api/conversations/"+cid+"/messages",null,false).body()).contains("hold");
        var conflict=json.writeValueAsString(Map.of("requestId","request-one","modelId",modelId,"message","changed"));
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",conflict,true).statusCode()).isEqualTo(409);
        var another=json.writeValueAsString(Map.of("requestId","request-two","modelId",modelId,"message","Hello"));
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",another,true).statusCode()).isEqualTo(409);
        assertThat(admin.request("POST","/api/tasks/"+taskId+"/cancel","{}",true).body()).contains("CANCELLED");
        assertThat(admin.request("POST","/api/conversations/"+cid+"/tasks",body,true).body()).contains("CANCELLED",taskId);
        awaitTask(admin,taskId,"executionState","STOPPED");
        var concurrentBody=json.writeValueAsString(Map.of("requestId","concurrent","modelId",modelId,"message","hold"));
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int i=0;i<8;i++) submissions.add(pool.submit(() -> admin.request("POST","/api/conversations/"+cid+"/tasks",concurrentBody,true)));
            var ids=new HashSet<String>();
            for(var submitted:submissions) { var result=submitted.get(); assertThat(result.statusCode()).isEqualTo(202); ids.add(json.readTree(result.body()).get("id").asText()); }
            assertThat(ids).hasSize(1);
            taskId=ids.iterator().next();
        }
        var secondConversation=admin.request("POST","/api/conversations","{}",true);
        var cid2=json.readTree(secondConversation.body()).get("id").asText();
        var running2=admin.request("POST","/api/conversations/"+cid2+"/tasks",json.writeValueAsString(Map.of("requestId","parallel","modelId",modelId,"message","hold")),true);
        assertThat(running2.statusCode()).isEqualTo(202);
        var task2=json.readTree(running2.body()).get("id").asText();
        var thirdConversation=admin.request("POST","/api/conversations","{}",true);
        var cid3=json.readTree(thirdConversation.body()).get("id").asText();
        assertThat(admin.request("POST","/api/conversations/"+cid3+"/tasks",json.writeValueAsString(Map.of("requestId","over-capacity","modelId",modelId,"message","hold")),true).statusCode()).isEqualTo(429);
        admin.request("POST","/api/tasks/"+taskId+"/cancel","{}",true); admin.request("POST","/api/tasks/"+task2+"/cancel","{}",true);
        awaitTask(admin,taskId,"executionState","STOPPED"); awaitTask(admin,task2,"executionState","STOPPED");
        var completed=admin.request("POST","/api/conversations/"+cid+"/tasks",another,true);
        assertThat(completed.statusCode()).isEqualTo(202);
        var completedId=json.readTree(completed.body()).get("id").asText(); awaitTask(admin,completedId,"status","COMPLETED");
        assertThat(admin.request("POST","/api/tasks/"+completedId+"/cancel","{}",true).body()).contains("COMPLETED");
        assertThat(admin.request("GET","/api/conversations/"+cid+"/messages",null,false).body()).contains("Fixture reply");
        var failure=admin.request("POST","/api/conversations/"+cid+"/tasks",json.writeValueAsString(Map.of("requestId","failure","modelId",modelId,"message","fail")),true);
        var failedId=json.readTree(failure.body()).get("id").asText(); awaitTask(admin,failedId,"status","FAILED");
        assertThat(admin.request("GET","/api/tasks/"+failedId,null,false).body()).doesNotContain("private-provider-detail");
        var late=admin.request("POST","/api/conversations/"+cid2+"/tasks",json.writeValueAsString(Map.of("requestId","late","modelId",modelId,"message","late")),true);
        var lateId=json.readTree(late.body()).get("id").asText();
        admin.request("POST","/api/tasks/"+lateId+"/cancel","{}",true); awaitTask(admin,lateId,"executionState","STOPPED");
        assertThat(admin.request("GET","/api/tasks/"+lateId,null,false).body()).contains("CANCELLED");
        assertThat(admin.request("GET","/api/conversations/"+cid2+"/messages",null,false).body()).doesNotContain("Fixture reply");

        var createdUser=admin.request("POST","/api/admin/users","{\"username\":\"task-user\",\"displayName\":\"Task user\"}",true);
        var user=json.readTree(createdUser.body()); var userId=user.get("user").get("id").asText();
        var ordinary=new Browser(); ordinary.login("task-user",user.get("temporaryPassword").asText()); ordinary.csrf();
        ordinary.request("POST","/api/auth/password",json.writeValueAsString(Map.of("currentPassword",user.get("temporaryPassword").asText(),"newPassword","Task-user-password")),true);
        ordinary.login("task-user","Task-user-password"); ordinary.csrf();
        assertThat(ordinary.request("GET","/api/tasks/"+taskId,null,false).statusCode()).isEqualTo(404);
        assertThat(ordinary.request("POST","/api/tasks/"+taskId+"/cancel","{}",true).statusCode()).isEqualTo(404);
        assertThat(ordinary.request("GET","/api/conversations/"+cid+"/messages",null,false).statusCode()).isEqualTo(404);
        assertThat(ordinary.request("POST","/api/conversations/"+cid+"/tasks",body,true).statusCode()).isEqualTo(404);
        var ownConversation=ordinary.request("POST","/api/conversations","{}",true);
        var ownId=json.readTree(ownConversation.body()).get("id").asText();
        assertThat(ordinary.request("POST","/api/conversations/"+ownId+"/tasks",body,true).statusCode()).isEqualTo(404);
        var ownBody=json.writeValueAsString(Map.of("requestId","request-one","modelId",modelId,"message","hold"));
        assertThat(ordinary.request("POST","/api/conversations/"+ownId+"/tasks",ownBody,true).statusCode()).isEqualTo(403);
        admin.request("PUT","/api/admin/users/"+userId+"/model-access",json.writeValueAsString(Map.of("modelIds",List.of(modelId))),true);
        var sameKeyOtherOwner=ordinary.request("POST","/api/conversations/"+ownId+"/tasks",ownBody,true);
        assertThat(sameKeyOtherOwner.statusCode()).isEqualTo(202);
        var ownTaskId=json.readTree(sameKeyOtherOwner.body()).get("id").asText();
        assertThat(ownTaskId).isNotEqualTo(taskId);
        assertThat(admin.request("GET","/api/tasks/"+ownTaskId,null,false).statusCode()).isEqualTo(404);
        ordinary.request("POST","/api/tasks/"+ownTaskId+"/cancel","{}",true);
        awaitTask(ordinary,ownTaskId,"executionState","STOPPED");

        var storageFailure=admin.request("POST","/api/conversations/"+cid3+"/tasks",json.writeValueAsString(Map.of("requestId","storage-failure","modelId",modelId,"message","late")),true);
        var storageId=json.readTree(storageFailure.body()).get("id").asText();
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try { Thread.sleep(20000); }
        finally { MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec(); }
        awaitTask(admin,storageId,"status","FAILED");

    }
    void awaitTask(Browser browser,String id,String field,String expected) throws Exception {
        long until=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        String actual="";
        while(System.nanoTime()<until) {
            var result=browser.request("GET","/api/tasks/"+id,null,false); assertThat(result.statusCode()).isEqualTo(200);
            actual=json.readTree(result.body()).get(field).asText();
            if(actual.equals(expected)) return;
            Thread.sleep(20);
        }
        assertThat(actual).isEqualTo(expected);
    }
}
