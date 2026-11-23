package io.iwork.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    private record Account(
            Caller caller, String hash, boolean enabled, int failures, Timestamp lockedUntil) {}

    public IdentityService(
            JdbcTemplate db,
            TransactionTemplate tx,
            Clock clock,
            @Value("${iwork.bootstrap.username:admin}") String username,
            @Value("${iwork.bootstrap.password:}") String password) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.bootstrapUsername = username;
        this.bootstrapPassword = password;
    }

    private List<Account> accounts(String condition, Object... args) {
        return db.query(
                "SELECT * FROM users WHERE " + condition,
                (r, n) ->
                        new Account(
                                new Caller(
                                        r.getString("id"),
                                        r.getString("username"),
                                        r.getString("display_name"),
                                        r.getString("role"),
                                        r.getBoolean("must_change_password"),
                                        r.getLong("auth_version")),
                                r.getString("password_hash"),
                                r.getBoolean("enabled"),
                                r.getInt("failed_attempts"),
                                r.getTimestamp("locked_until")),
                args);
    }

    public void bootstrap() {
        if (!accounts("role = 'ADMIN'").isEmpty()) return;
        if (bootstrapPassword.isEmpty())
            throw new IllegalStateException("Initial administrator secret is required");
        validatePassword(bootstrapPassword);
        validateUsername(bootstrapUsername);
        db.update(
                "INSERT INTO users(id, username, display_name, password_hash, role) VALUES(?,?,?,?, 'ADMIN')",
                UUID.randomUUID().toString(),
                bootstrapUsername,
                "管理员",
                passwords.encode(bootstrapPassword));
    }

    public Caller authenticate(String username, String password) {
        var caller =
                tx.execute(
                        status -> {
                            var rows = accounts("username = ? FOR UPDATE", username);
                            if (rows.isEmpty()) return null;
                            var account = rows.getFirst();
                            if (!account.enabled()
                                    || account.lockedUntil() != null
                                            && account.lockedUntil()
                                                    .toInstant()
                                                    .isAfter(clock.instant())) return null;
                            if (password == null
                                    || password.getBytes(StandardCharsets.UTF_8).length > 72
                                    || !passwords.matches(password, account.hash())) {
                                int failures =
                                        account.lockedUntil() != null ? 1 : account.failures() + 1;
                                db.update(
                                        "UPDATE users SET failed_attempts=?, locked_until=? WHERE id=?",
                                        failures,
                                        failures >= 5
                                                ? Timestamp.from(
                                                        clock.instant()
                                                                .plus(Duration.ofMinutes(15)))
                                                : null,
                                        account.caller().id());
                                return null;
                            }
                            db.update(
                                    "UPDATE users SET failed_attempts=0, locked_until=NULL WHERE id=?",
                                    account.caller().id());
                            return account.caller();
                        });
        if (caller == null) throw new ApiFailure(401, "invalid_credentials");
        return caller;
    }

    public Caller verify(Caller caller, boolean lock) {
        var rows = accounts("id=?" + (lock ? " FOR UPDATE" : ""), caller.id());
        // 排队任务和长连接可能仍持有旧身份。改密或停用会递增账号版本，使旧身份失效。
        if (rows.isEmpty()
                || !rows.getFirst().enabled()
                || rows.getFirst().caller().authVersion() != caller.authVersion())
            throw new ApiFailure(401, "session_expired");
        return rows.getFirst().caller();
    }

    public record User(
            String id,
            String username,
            String displayName,
            String role,
            boolean enabled,
            boolean mustChangePassword) {}

    public record IssuedPassword(User user, String temporaryPassword) {}

    private User view(Account a) {
        var c = a.caller();
        return new User(
                c.id(),
                c.username(),
                c.displayName(),
                c.role(),
                a.enabled(),
                c.mustChangePassword());
    }

    private void requireAdmin(Caller caller) {
        var live = verify(caller, true);
        if (!live.role().equals("ADMIN") || live.mustChangePassword())
            throw new ApiFailure(403, "forbidden");
    }

    public record UserPage(List<User> items, int page, int size, boolean hasMore) {}

    public UserPage listUsers(Caller caller, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new ApiFailure(400, "invalid_pagination");
        return tx.execute(
                status -> {
                    requireAdmin(caller);
                    var rows =
                            accounts(
                                    "1=1 ORDER BY username LIMIT ? OFFSET ?",
                                    size + 1,
                                    page * size);
                    return new UserPage(
                            rows.stream().limit(size).map(this::view).toList(),
                            page,
                            size,
                            rows.size() > size);
                });
    }

    public IssuedPassword createUser(Caller caller, String username, String displayName) {
        validateUsername(username);
        if (displayName == null || displayName.isBlank() || displayName.length() > 100)
            throw new ApiFailure(400, "invalid_display_name");
        try {
            return tx.execute(
                    status -> {
                        requireAdmin(caller);
                        var id = UUID.randomUUID().toString();
                        var temporary = temporaryPassword();
                        db.update(
                                "INSERT INTO users(id,username,display_name,password_hash,role) VALUES(?,?,?,?, 'USER')",
                                id,
                                username,
                                displayName.strip(),
                                passwords.encode(temporary));
                        return new IssuedPassword(view(accounts("id=?", id).getFirst()), temporary);
                    });
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw new ApiFailure(409, "username_exists");
        }
    }

    public User setEnabled(Caller caller, String id, boolean enabled) {
        return tx.execute(
                status -> {
                    requireAdmin(caller);
                    var account = target(id);
                    if (account.caller().role().equals("ADMIN"))
                        throw new ApiFailure(403, "administrator_protected");
                    if (account.enabled() != enabled)
                        db.update(
                                "UPDATE users SET enabled=?, auth_version=auth_version+1 WHERE id=?",
                                enabled,
                                id);
                    return view(accounts("id=?", id).getFirst());
                });
    }

    public IssuedPassword resetPassword(Caller caller, String id) {
        return tx.execute(
                status -> {
                    requireAdmin(caller);
                    var account = target(id);
                    if (account.caller().role().equals("ADMIN"))
                        throw new ApiFailure(403, "administrator_protected");
                    var temporary = temporaryPassword();
                    replacePassword(id, temporary, true);
                    return new IssuedPassword(view(accounts("id=?", id).getFirst()), temporary);
                });
    }

    private Account target(String id) {
        var rows = accounts("id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ApiFailure(404, "not_found");
        return rows.getFirst();
    }

    private String temporaryPassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void recoverAdministrator(String password) {
        validatePassword(password);
        tx.executeWithoutResult(
                status -> {
                    var rows =
                            accounts("username=? AND role='ADMIN' FOR UPDATE", bootstrapUsername);
                    if (rows.isEmpty()) throw new ApiFailure(404, "administrator_not_found");
                    replacePassword(rows.getFirst().caller().id(), password, true);
                });
    }

    public Caller changePassword(Caller caller, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        return tx.execute(
                status -> {
                    verify(caller, true);
                    var account = accounts("id=?", caller.id()).getFirst();
                    if (currentPassword == null
                            || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                            || !passwords.matches(currentPassword, account.hash()))
                        throw new ApiFailure(400, "wrong_current_password");
                    if (passwords.matches(newPassword, account.hash()))
                        throw new ApiFailure(400, "password_unchanged");
                    replacePassword(caller.id(), newPassword, false);
                    return accounts("id=?", caller.id()).getFirst().caller();
                });
    }

    private void replacePassword(String id, String password, boolean temporary) {
        validatePassword(password);
        db.update(
                "UPDATE users SET password_hash=?, must_change_password=?, auth_version=auth_version+1, failed_attempts=0, locked_until=NULL WHERE id=?",
                passwords.encode(password),
                temporary,
                id);
    }

    static void validatePassword(String value) {
        if (value == null
                || value.codePointCount(0, value.length()) < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiFailure(400, "invalid_password");
    }

    static void validateUsername(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))
            throw new ApiFailure(400, "invalid_username");
    }
}
