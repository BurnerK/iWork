package io.iwork.identity;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A deployment-only entry point. Passwords never travel in process arguments. */
public final class AdministratorRecovery {
    private AdministratorRecovery() {}

    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
    })
    static class RecoveryConfiguration {
        @Bean
        IdentityService recoveryIdentity(
                JdbcTemplate db,
                PlatformTransactionManager transactionManager,
                @Value("${iwork.bootstrap.username:admin}") String username) {
            return new IdentityService(
                    db,
                    new TransactionTemplate(transactionManager),
                    Clock.systemUTC(),
                    username,
                    "");
        }
    }

    public static int run() {
        char[] secret = null;
        try {
            String file = System.getenv("IWORK_RECOVERY_PASSWORD_FILE");
            if (file != null && !file.isBlank()) {
                var path = Path.of(file);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 512)
                    throw new IllegalArgumentException();
                Set<PosixFilePermission> permissions =
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (permissions.stream()
                        .anyMatch(
                                p ->
                                        p.name().startsWith("GROUP_")
                                                || p.name().startsWith("OTHERS_")))
                    throw new IllegalArgumentException();
                secret = Files.readString(path).stripTrailing().toCharArray();
            } else {
                var console = System.console();
                if (console == null) throw new IllegalArgumentException();
                secret = console.readPassword("New temporary administrator password: ");
            }
            if (secret == null) throw new IllegalArgumentException();
            IdentityService.validatePassword(new String(secret));
            var app = new SpringApplication(RecoveryConfiguration.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run()) {
                context.getBean(IdentityService.class).recoverAdministrator(new String(secret));
            }
            System.out.println(
                    "Administrator password replaced; all prior sessions revoked. Change it at next login.");
            return 0;
        } catch (Exception error) {
            System.err.println(
                    "Recovery failed. Use a private regular password file (0600) or an interactive terminal, and verify database access.");
            return 1;
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }
}
