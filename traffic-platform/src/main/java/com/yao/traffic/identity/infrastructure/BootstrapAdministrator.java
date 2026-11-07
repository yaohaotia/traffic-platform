package com.yao.traffic.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.List;

/** Explicit one-time administrator creation; never enabled or seeded by default. */
@Component
@ConditionalOnProperty(prefix = "traffic.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapAdministrator implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    private final String username;
    private final String password;

    public BootstrapAdministrator(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformTransactionManager manager,
            @Value("${traffic.bootstrap.username}") String username,
            @Value("${traffic.bootstrap.password}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.transactions = new TransactionTemplate(manager);
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,100}"))
            throw new IllegalArgumentException("Bootstrap username must be 3-100 ASCII letters, digits, dots, underscores or hyphens");
        if (password == null || password.length() < 12 || password.length() > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Bootstrap password must have 12-128 characters and no more than 72 UTF-8 bytes");
        // Existing accounts are never overwritten or elevated by a repeated startup.
        if (exists()) return;
        String passwordHash = encoder.encode(password);
        transactions.executeWithoutResult(status -> {
            if (exists()) return;
            long user = insertWithId("INSERT INTO identity_user (username, password_hash, enabled, auth_version) VALUES (?, ?, TRUE, 0)",
                    username, passwordHash);
            long role = codeId("identity_role", "ADMIN");
            for (String permission : List.of("identity:user:manage", "region:read", "campaign:manage",
                      "report:submit", "report:read", "report:review", "detection:manage", "message:manage",
                      "review:claim", "review:decide", "review:recheck", "review:manage",
                      "wallet:read", "wallet:withdraw", "reward:manage", "withdrawal:manage", "notification:read", "operations:read")) {
                long permissionId = codeId("identity_permission", permission);
                Integer assigned = jdbc.queryForObject("SELECT COUNT(*) FROM identity_role_permission WHERE role_id = ? AND permission_id = ?",
                        Integer.class, role, permissionId);
                if (assigned != null && assigned == 0)
                    jdbc.update("INSERT INTO identity_role_permission (role_id, permission_id) VALUES (?, ?)", role, permissionId);
            }
            jdbc.update("INSERT INTO identity_user_role (user_id, role_id) VALUES (?, ?)", user, role);
        });
    }

    private boolean exists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    private long codeId(String table, String code) {
        // Table names originate only from the two constants in this class, never from HTTP input.
        List<Long> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? insertWithId("INSERT INTO " + table + " (code) VALUES (?)", code) : ids.getFirst();
    }

    private long insertWithId(String sql, String... parameters) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < parameters.length; index++) statement.setString(index + 1, parameters[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return the generated identity");
        return key.longValue();
    }
}
