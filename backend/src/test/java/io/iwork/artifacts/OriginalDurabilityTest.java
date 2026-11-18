package io.iwork.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.iwork.identity.ApiFailure;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OriginalDurabilityTest {
    @Test
    @DisplayName("A directory sync failure after rename prevents material registration")
    void directorySyncFailurePreventsRegistration(@TempDir Path root) throws Exception {
        var db = mock(JdbcTemplate.class);
        var tx = mock(TransactionTemplate.class);
        var materials = new MaterialService(root, db, tx, null);
        var directory = root.resolve("originals");
        var permissions = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("-wx------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(
                    Files.isReadable(directory), "Requires an unprivileged POSIX user");
            Files.delete(Files.createTempFile(directory, "writable-", ".probe"));
            assertThatThrownBy(
                            () ->
                                    materials.upload(
                                            null,
                                            "source.txt",
                                            1,
                                            new ByteArrayInputStream(new byte[] {'x'})))
                    .isInstanceOf(ApiFailure.class)
                    .hasMessage("material_storage_unavailable");
            verifyNoInteractions(db, tx);
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
