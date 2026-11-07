package com.yao.traffic.identity.infrastructure;

import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Redis is the revocation/session source. Missing or unavailable sessions never authenticate. */
@Component
public class RedisSessionStore {
    public static final String PREFIX = "traffic:auth:session:";
    public static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'ACTIVE', 'userId', ARGV[1],
                'authVersion', ARGV[2], 'refreshHash', ARGV[3], 'absoluteExpiresAtMs', ARGV[4])
            redis.call('PEXPIREAT', KEYS[1], ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
            local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'absoluteExpiresAtMs'))
            local now = redis.call('TIME')
            local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            if not expiresAt or expiresAt <= nowMs then return 0 end
            if redis.call('HGET', KEYS[1], 'userId') ~= ARGV[3] then return 0 end
            if redis.call('HGET', KEYS[1], 'authVersion') ~= ARGV[4] then return 0 end
            if redis.call('HGET', KEYS[1], 'refreshHash') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'refreshHash', ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'REVOKED')
            redis.call('HDEL', KEYS[1], 'refreshHash')
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) { this.redis = redis; }

    public OpenedSession create(long userId, long authVersion) {
        Instant absoluteExpiry = Instant.ofEpochMilli(Instant.now().plus(SESSION_LIFETIME).toEpochMilli());
        for (int attempt = 0; attempt < 3; attempt++) {
            String sid = UUID.randomUUID().toString();
            String refresh = randomRefreshToken(sid);
            Long created = dependency(() -> redis.execute(CREATE, List.of(PREFIX + sid), Long.toString(userId),
                    Long.toString(authVersion), hash(refresh), Long.toString(absoluteExpiry.toEpochMilli())));
            if (Long.valueOf(1).equals(created))
                return new OpenedSession(new Session(sid, userId, authVersion, absoluteExpiry), refresh);
        }
        throw new DependencyUnavailableException("Redis session allocation", null);
    }

    public Session requireActive(String sid) {
        Map<Object, Object> fields = dependency(() -> redis.opsForHash().entries(PREFIX + sid));
        try {
            if (!"ACTIVE".equals(fields.get("state"))) throw invalidSession();
            long userId = Long.parseLong((String) fields.get("userId"));
            long version = Long.parseLong((String) fields.get("authVersion"));
            Instant expiry = Instant.ofEpochMilli(Long.parseLong((String) fields.get("absoluteExpiresAtMs")));
            if (!Instant.now().isBefore(expiry) || userId <= 0 || version < 0) throw invalidSession();
            return new Session(sid, userId, version, expiry);
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidSession();
        }
    }

    public String rotate(String oldToken, Session session) {
        String replacement = randomRefreshToken(session.sid());
        Long result = dependency(() -> redis.execute(ROTATE, List.of(PREFIX + session.sid()), hash(oldToken),
                hash(replacement), Long.toString(session.userId()), Long.toString(session.authVersion())));
        if (!Long.valueOf(1).equals(result)) throw invalidSession();
        return replacement;
    }

    public void revoke(String sid) {
        dependency(() -> redis.execute(REVOKE, List.of(PREFIX + sid)));
    }

    public String sidFromRefresh(String token) {
        if (token == null || token.length() > 512) throw invalidSession();
        int separator = token.indexOf('.');
        if (separator != 36) throw invalidSession();
        String sid = token.substring(0, separator);
        try {
            if (!UUID.fromString(sid).toString().equals(sid)
                    || !token.substring(separator + 1).matches("[A-Za-z0-9_-]{43}")) throw invalidSession();
            return sid;
        } catch (IllegalArgumentException exception) { throw invalidSession(); }
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static String randomRefreshToken(String sid) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return sid + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static BusinessException invalidSession() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "登录会话无效或已过期");
    }

    private <T> T dependency(Supplier<T> operation) {
        try { return operation.get(); }
        catch (org.springframework.data.redis.RedisConnectionFailureException exception) {
            throw new DependencyUnavailableException("Redis", exception);
        }
        catch (org.springframework.dao.DataAccessException exception) {
            throw new DependencyUnavailableException("Redis", exception);
        }
    }

    public record Session(String sid, long userId, long authVersion, Instant absoluteExpiresAt) { }
    public record OpenedSession(Session session, String refreshToken) { }
}
