package io.iwork;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@AutoConfigureTestRestTemplate
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "iwork.files-directory=target/test-files",
            "iwork.bootstrap.password=Test-bootstrap-password",
            "springdoc.api-docs.enabled=true",
            "springdoc.api-docs.version=openapi_3_0",
            "springdoc.paths-to-match=/api/**",
            "springdoc.override-with-generic-response=false",
            "springdoc.use-fqn=true"
        })
class ApplicationTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.7")
                    .withUrlParam("connectTimeout", "3000")
                    .withUrlParam("socketTimeout", "3000");

    @Container
    static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:8.2.3-alpine")
                    .withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void redis(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired TestRestTemplate http;
    @Autowired WebApplicationContext web;

    @Test
    void openApiMatches() throws Exception {
        assertThat(http.getForEntity("/v3/api-docs.yaml", String.class).getStatusCode().value())
                .isEqualTo(403);
        OpenApiContract.verify(web);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Readiness is public but only reports status")
    void readinessIsPublicButOnlyReportsStatus() {
        var response = http.getForEntity("/actuator/health/readiness", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Private and diagnostic endpoints are not public")
    void privateAndDiagnosticEndpointsAreNotPublic() {
        for (var path :
                new String[] {
                    "/api/tasks",
                    "/actuator/env",
                    "/actuator/health",
                    "/actuator/health/readiness/db"
                }) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode().value())
                    .as(path)
                    .isEqualTo(path.startsWith("/api/") ? 401 : 403);
            assertThat(response.getBody())
                    .doesNotContain("jdbc:", "password", "exception", "trace");
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Database outage fails readiness but not liveness")
    void databaseOutageFailsReadinessButNotLiveness() {
        var docker = MYSQL.getDockerClient();
        docker.pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            var readiness = http.getForEntity("/actuator/health/readiness", String.class);
            assertThat(readiness.getStatusCode().value()).isEqualTo(503);
            assertThat(readiness.getBody()).isEqualTo("{\"status\":\"DOWN\"}");
            var liveness = http.getForEntity("/actuator/health/liveness", String.class);
            assertThat(liveness.getStatusCode().value()).isEqualTo(200);
            assertThat(liveness.getBody()).isEqualTo("{\"status\":\"UP\"}");
        } finally {
            docker.unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Unavailable file storage fails readiness")
    void unavailableFileStorageFailsReadiness() throws Exception {
        var directory = Path.of("target/test-files");
        var backup = Path.of("target/test-files-backup");
        Files.move(directory, backup);
        try {
            Files.createFile(directory);
            var response = http.getForEntity("/actuator/health/readiness", String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).isEqualTo("{\"status\":\"DOWN\"}");
        } finally {
            Files.deleteIfExists(directory);
            Files.move(backup, directory);
        }
    }
}
