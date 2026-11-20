package io.iwork.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginalDurabilityTest {
    @Test
    @DisplayName("A directory sync failure after rename removes the original")
    void directorySyncFailureRemovesOriginal(@TempDir Path root) throws Exception {
        var materials = new MaterialService(root, null, null, null, 1, 1, 1, 1, 1);
        var directory = root.resolve("originals");
        var permissions = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("-wx------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(
                    Files.isReadable(directory), "Requires an unprivileged POSIX user");
            Files.delete(Files.createTempFile(directory, "writable-", ".probe"));
            assertThatThrownBy(() -> materials.writeOriginal("source", new byte[] {'x'}))
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
