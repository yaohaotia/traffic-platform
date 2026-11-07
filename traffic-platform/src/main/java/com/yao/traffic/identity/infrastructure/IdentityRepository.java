package com.yao.traffic.identity.infrastructure;

import com.yao.traffic.identity.domain.IdentityProfile;
import com.yao.traffic.identity.domain.IdentityUser;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class IdentityRepository {
    private static final RowMapper<IdentityUser> USER_MAPPER = (rs, row) -> new IdentityUser(rs.getLong("id"),
            rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getLong("auth_version"));
    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<IdentityUser> findByUsername(String username) {
        return database(() -> jdbc.query("SELECT id, username, password_hash, enabled, auth_version FROM identity_user WHERE username = ?",
                USER_MAPPER, username).stream().findFirst());
    }

    public Optional<IdentityUser> findById(long userId) {
        return database(() -> jdbc.query("SELECT id, username, password_hash, enabled, auth_version FROM identity_user WHERE id = ?",
                USER_MAPPER, userId).stream().findFirst());
    }

    public IdentityProfile profile(IdentityUser user) {
        return database(() -> {
            List<String> roles = jdbc.queryForList("""
                    SELECT r.code FROM identity_role r JOIN identity_user_role ur ON ur.role_id = r.id
                    WHERE ur.user_id = ? ORDER BY r.code
                    """, String.class, user.id());
            List<String> permissions = jdbc.queryForList("""
                    SELECT DISTINCT p.code FROM identity_permission p
                    JOIN identity_role_permission rp ON rp.permission_id = p.id
                    JOIN identity_user_role ur ON ur.role_id = rp.role_id
                    WHERE ur.user_id = ? ORDER BY p.code
                    """, String.class, user.id());
            List<String> regions = jdbc.queryForList("""
                    SELECT r.code FROM identity_region r JOIN identity_user_region ur ON ur.region_id = r.id
                    WHERE ur.user_id = ? ORDER BY r.code
                    """, String.class, user.id());
            return new IdentityProfile(user.id(), user.username(), roles, permissions, regions);
        });
    }

    public int updateEnabled(long userId, boolean enabled) {
        return database(() -> jdbc.update("""
                UPDATE identity_user SET enabled = ?, auth_version = auth_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, enabled, userId));
    }

    public Optional<Region> regionForUser(String code, long userId, boolean canManageUsers) {
        return database(() -> jdbc.query("""
                SELECT r.id, r.code, r.name FROM identity_region r WHERE r.code = ?
                AND (? = TRUE OR EXISTS (SELECT 1 FROM identity_user_region ur WHERE ur.region_id = r.id AND ur.user_id = ?))
                """, (rs, row) -> new Region(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                code, canManageUsers, userId).stream().findFirst());
    }

    private <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException exception) { throw new DependencyUnavailableException("MySQL", exception); }
    }

    public record Region(long id, String code, String name) { }
}
