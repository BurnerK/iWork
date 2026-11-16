package io.iwork;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestRestTemplate
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"iwork.files-directory=target/test-files", "iwork.bootstrap.password=Test-bootstrap-password"})
class ApplicationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withUrlParam("connectTimeout", "3000").withUrlParam("socketTimeout", "3000");

    @Container
    static final org.testcontainers.containers.GenericContainer<?> REDIS =
        new org.testcontainers.containers.GenericContainer<>("redis:8.2.3-alpine").withExposedPorts(6379);
    @org.springframework.test.context.DynamicPropertySource
    static void redis(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired
    TestRestTemplate http;

    @Test
    void readinessIsPublicButOnlyReportsStatus() {
        var response = http.getForEntity("/actuator/health/readiness", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void privateAndDiagnosticEndpointsAreNotPublic() {
        for (var path : new String[]{"/api/tasks", "/actuator/env", "/actuator/health", "/actuator/health/readiness/db"}) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(path.startsWith("/api/") ? 401 : 403);
            assertThat(response.getBody()).doesNotContain("jdbc:", "password", "exception", "trace");
        }
    }

    @Test
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
