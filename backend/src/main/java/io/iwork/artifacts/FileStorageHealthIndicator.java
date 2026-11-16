package io.iwork.artifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

@Component("fileStorage")
class FileStorageHealthIndicator extends AbstractHealthIndicator {
    private final Path directory;

    FileStorageHealthIndicator(@Value("${iwork.files-directory}") Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        verifyWritable();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws IOException {
        verifyWritable();
        builder.up();
    }

    private void verifyWritable() throws IOException {
        var probe = Files.createTempFile(directory, ".health-", ".tmp");
        Files.delete(probe);
    }
}
