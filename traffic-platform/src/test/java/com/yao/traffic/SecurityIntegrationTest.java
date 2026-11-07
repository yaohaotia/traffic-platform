package com.yao.traffic;

import com.yao.traffic.identity.infrastructure.BootstrapAdministrator;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL + Redis + an actual HTTP server. Missing dependencies fail, never skip this gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactionManager;
    @Value("${traffic.security.jwt-secret}") String secret;

    private static final String PASSWORD = "Testing-traffic-2026!";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> sessionKeys = ConcurrentHashMap.newKeySet();
    private String passwordHash;

    @BeforeAll void encodeTestPassword() { passwordHash = passwords.encode(PASSWORD); }

    @BeforeEach
    void seedDedicatedDatabase() {
        // This profile names an isolated test database and never shares the old application's database.
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("traffic_platform_it");
        jdbc.update("DELETE FROM identity_user_region");
        jdbc.update("DELETE FROM identity_user_role");
        jdbc.update("DELETE FROM identity_role_permission");
        jdbc.update("DELETE FROM identity_permission");
        jdbc.update("DELETE FROM identity_role");
        jdbc.update("DELETE FROM identity_user");
        jdbc.update("DELETE FROM identity_region");
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(101,'alice',?,TRUE,0)", passwordHash);
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(102,'bob',?,TRUE,0)", passwordHash);
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(103,'operator',?,TRUE,0)", passwordHash);
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(104,'disabled',?,FALSE,0)", passwordHash);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(201,'USER'),(202,'ADMIN')");
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES(301,'region:read'),(302,'identity:user:manage')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(101,201),(102,201),(103,202),(104,201)");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(201,301),(202,301),(202,302)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(401,'NORTH','North campus'),(402,'SOUTH','South campus')");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(101,401),(102,402)");
    }

    @AfterEach void removeOnlySessionsCreatedByThisTest() {
        sessionKeys.forEach(redis::delete);
        sessionKeys.clear();
    }

    @Test
    void migratesRealMysqlAndEnforcesUsernameUniqueness() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        flyway.validate();
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion()).contains("1", "2");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(999,'alice',?,TRUE,0)", passwordHash))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(passwordHash).doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, passwordHash)).isTrue();
    }

    @Test
    void logsInAndReturnsCurrentServerSideIdentity() throws Exception {
        Tokens tokens = login("alice");
        assertThat(tokens.expiresIn()).isBetween(1L, 300L);
        JWTClaimsSet claims = SignedJWT.parse(tokens.access()).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("traffic-platform");
        assertThat(claims.getAudience()).contains("traffic-web");
        assertThat(claims.getSubject()).isEqualTo("101");
        assertThat(claims.getStringClaim("sid")).isEqualTo(tokens.sid());
        assertThat(claims.getClaim("ver")).isNotNull();
        assertThat(claims.getIssueTime()).isNotNull();
        assertThat(claims.getJWTID()).isNotBlank();
        Reply me = call("GET", "/api/v1/auth/me", null, tokens.access());
        assertThat(me.status()).isEqualTo(200);
        assertThat(((Number) me.json().get("userId")).longValue()).isEqualTo(101L);
        assertThat(me.json().get("username")).isEqualTo("alice");
        assertThat(asStrings(me.json().get("roles"))).contains("USER");
        assertThat(asStrings(me.json().get("permissions"))).contains("region:read").doesNotContain("identity:user:manage");
        assertThat(asStrings(me.json().get("regionCodes"))).containsExactly("NORTH");
        assertThat(me.body()).doesNotContain("password_hash", passwordHash, PASSWORD, tokens.refresh());
        assertThat(me.requestId()).isNotBlank();
    }

    @Test
    void wrongPasswordUnknownAndDisabledAccountsAllRejectLogin() throws Exception {
        for (Map<String, ?> body : List.of(Map.of("username", "alice", "password", "wrong"),
                Map.of("username", "unknown", "password", PASSWORD), Map.of("username", "disabled", "password", PASSWORD))) {
            Reply result = call("POST", "/api/v1/auth/login", body, null);
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.body()).doesNotContain(passwordHash, "stackTrace", PASSWORD);
        }
    }

    @Test
    void rejectsBlankLoginAndRefreshInput() throws Exception {
        assertThat(call("POST", "/api/v1/auth/login", Map.of("username", "", "password", ""), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", ""), null).status()).isEqualTo(400);
    }

    @Test
    void distinguishesUnauthenticatedAndForbiddenRequests() throws Exception {
        Reply missing = call("GET", "/api/v1/auth/me", null, null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(((Number) missing.json().get("status")).intValue()).isEqualTo(401);
        assertThat(missing.json().get("path")).isEqualTo("/api/v1/auth/me");
        assertThat(missing.requestId()).isNotBlank();
        Tokens alice = login("alice");
        assertThat(call("PUT", "/api/v1/identity/users/102/enabled", Map.of("enabled", false), alice.access()).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity_user WHERE id=102", Boolean.class)).isTrue();
    }

    @Test
    void validatesJwtSignatureIssuerAudienceExpiryAndMandatoryClaims() throws Exception {
        Tokens tokens = login("alice");
        JWTClaimsSet claims = SignedJWT.parse(tokens.access()).getJWTClaimsSet();
        List<String> invalid = new ArrayList<>();
        invalid.add(sign(claims, "another-integration-secret-at-least-32-bytes-2026"));
        invalid.add(changed(tokens.access(), builder -> builder.issuer("foreign-service")));
        invalid.add(changed(tokens.access(), builder -> builder.audience("foreign-client")));
        invalid.add(changed(tokens.access(), builder -> builder.expirationTime(Date.from(Instant.now().minusSeconds(300)))));
        invalid.add(changed(tokens.access(), builder -> builder.notBeforeTime(Date.from(Instant.now().plusSeconds(300)))));
        invalid.add(changed(tokens.access(), builder -> builder.claim("sid", null)));
        invalid.add(changed(tokens.access(), builder -> builder.claim("ver", null)));
        invalid.add(changed(tokens.access(), builder -> builder.claim("ver", 0.5)));
        invalid.add(changed(tokens.access(), builder -> builder.expirationTime(null)));
        invalid.add(changed(tokens.access(), builder -> builder.issueTime(null)));
        invalid.add(changed(tokens.access(), builder -> builder.jwtID(null)));
        invalid.add(changed(tokens.access(), builder -> builder.subject("102")));
        invalid.add(new PlainJWT(claims).serialize());
        invalid.add("not.a.valid-jwt");
        for (String token : invalid) {
            assertThat(call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(401);
        }
    }

    @Test
    void requiresExistingLiveRedisSessionEvenForValidSignature() throws Exception {
        Tokens tokens = login("alice");
        redis.delete(tokens.key());
        assertThat(call("GET", "/api/v1/auth/me", null, tokens.access()).status()).isEqualTo(401);
        assertThat(refresh(tokens.refresh()).status()).isEqualTo(401);
        assertThat(redis.hasKey(tokens.key())).isFalse();
    }

    @Test
    void redisStoresOnlyRefreshHashAndRefreshPreservesAbsoluteDeadline() throws Exception {
        Tokens original = login("alice");
        Map<Object, Object> before = redis.opsForHash().entries(original.key());
        assertThat(before.get("state")).isEqualTo("ACTIVE");
        assertThat(before.get("userId").toString()).isEqualTo("101");
        assertThat(before.get("refreshHash")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.refresh().getBytes(StandardCharsets.UTF_8))));
        assertThat(before.values()).doesNotContain(original.refresh(), original.access());
        long beforeTtl = redis.getExpire(original.key(), TimeUnit.MILLISECONDS);
        assertThat(beforeTtl).isBetween(Duration.ofHours(8).minusMinutes(1).toMillis(), Duration.ofHours(8).toMillis());
        Reply refreshed = refresh(original.refresh());
        assertThat(refreshed.status()).isEqualTo(200);
        Tokens replacement = tokens(refreshed);
        assertThat(replacement.refresh()).isNotEqualTo(original.refresh());
        assertThat(replacement.sid()).isEqualTo(original.sid());
        assertThat(replacement.sessionExpiresAt()).isEqualTo(original.sessionExpiresAt());
        assertThat(redis.opsForHash().get(original.key(), "absoluteExpiresAtMs")).isEqualTo(before.get("absoluteExpiresAtMs"));
        assertThat(redis.getExpire(original.key(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(beforeTtl);
        assertThat(refresh(original.refresh()).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/auth/me", null, replacement.access()).status()).isEqualTo(200);
        // Refresh does not invalidate the access credential that a simultaneous logout may use.
        assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(200);
    }

    @Test
    void accessExpiryCannotOutliveSessionAbsoluteDeadline() throws Exception {
        Tokens original = login("alice");
        Instant deadline = Instant.now().plusSeconds(90);
        redis.opsForHash().put(original.key(), "absoluteExpiresAtMs", String.valueOf(deadline.toEpochMilli()));
        redis.expireAt(original.key(), deadline);
        Reply refreshed = refresh(original.refresh());
        assertThat(refreshed.status()).isEqualTo(200);
        Tokens replacement = tokens(refreshed);
        assertThat(SignedJWT.parse(replacement.access()).getJWTClaimsSet().getExpirationTime().toInstant()).isBeforeOrEqualTo(deadline);
        assertThat(replacement.expiresIn()).isBetween(1L, 90L);
    }

    @Test
    void absoluteExpiredSessionCannotRefreshOrBeRecreated() throws Exception {
        Tokens original = login("alice");
        redis.opsForHash().put(original.key(), "absoluteExpiresAtMs", String.valueOf(Instant.now().minusSeconds(1).toEpochMilli()));
        assertThat(refresh(original.refresh()).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(401);
    }

    @Test
    void exactlyOneOfTwentyConcurrentRefreshRequestsSucceeds() throws Exception {
        Tokens original = login("alice");
        List<Reply> replies = concurrent(20, () -> refresh(original.refresh()));
        assertThat(replies.stream().filter(reply -> reply.status() == 200).count()).isEqualTo(1L);
        assertThat(replies.stream().filter(reply -> reply.status() == 401).count()).isEqualTo(19L);
        Tokens winner = tokens(replies.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow());
        assertThat(call("GET", "/api/v1/auth/me", null, winner.access()).status()).isEqualTo(200);
        assertThat(refresh(winner.refresh()).status()).isEqualTo(200);
    }

    @Test
    void logoutRevokesAccessAndRefreshWithoutRecreatingSession() throws Exception {
        Tokens original = login("alice");
        assertThat(call("POST", "/api/v1/auth/logout", null, original.access()).status()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(401);
        assertThat(refresh(original.refresh()).status()).isEqualTo(401);
        assertThat(redis.opsForHash().get(original.key(), "state")).isEqualTo("REVOKED");
        assertThat(redis.opsForHash().hasKey(original.key(), "refreshHash")).isFalse();
    }

    @Test
    void concurrentLogoutAndRefreshAlwaysLeaveSessionRevoked() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            Tokens original = login("alice");
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Reply> refreshed = executor.submit(() -> { start.await(); return refresh(original.refresh()); });
                Future<Reply> loggedOut = executor.submit(() -> { start.await(); return call("POST", "/api/v1/auth/logout", null, original.access()); });
                start.countDown();
                Reply refresh = refreshed.get(20, TimeUnit.SECONDS);
                assertThat(loggedOut.get(20, TimeUnit.SECONDS).status()).isEqualTo(204);
                assertThat(refresh.status()).isIn(200, 401);
                assertThat(redis.opsForHash().get(original.key(), "state")).isEqualTo("REVOKED");
                assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(401);
                if (refresh.status() == 200) {
                    Tokens replacement = tokens(refresh);
                    assertThat(call("GET", "/api/v1/auth/me", null, replacement.access()).status()).isEqualTo(401);
                    assertThat(refresh(replacement.refresh()).status()).isEqualTo(401);
                }
            }
        }
    }

    @Test
    void disablingUserInvalidatesExistingAccessAndRefresh() throws Exception {
        Tokens original = login("alice");
        Tokens operator = login("operator");
        assertThat(call("PUT", "/api/v1/identity/users/101/enabled", Map.of("enabled", false), operator.access()).status()).isIn(200, 204);
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity_user WHERE id=101", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT auth_version FROM identity_user WHERE id=101", Long.class)).isGreaterThan(0L);
        assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(401);
        assertThat(refresh(original.refresh()).status()).isEqualTo(401);
    }

    @Test
    void changedAuthVersionInvalidatesPreviouslyIssuedCredentials() throws Exception {
        Tokens original = login("alice");
        jdbc.update("UPDATE identity_user SET auth_version=auth_version+1 WHERE id=101");
        assertThat(call("GET", "/api/v1/auth/me", null, original.access()).status()).isEqualTo(401);
        assertThat(refresh(original.refresh()).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/auth/me", null, login("alice").access()).status()).isEqualTo(200);
    }

    @Test
    void permissionsAreReadFromCurrentDatabaseRatherThanStaleJwtClaims() throws Exception {
        Tokens original = login("alice");
        assertThat(call("GET", "/api/v1/identity/regions/NORTH", null, original.access()).status()).isEqualTo(200);
        jdbc.update("DELETE FROM identity_role_permission WHERE role_id=201 AND permission_id=301");
        assertThat(call("GET", "/api/v1/identity/regions/NORTH", null, original.access()).status()).isEqualTo(403);
    }

    @Test
    void regionMembershipIsCheckedAndAdministratorsCanReadAcrossRegions() throws Exception {
        Tokens alice = login("alice");
        Tokens bob = login("bob");
        Tokens operator = login("operator");
        assertThat(call("GET", "/api/v1/identity/regions/NORTH", null, alice.access()).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/identity/regions/SOUTH", null, alice.access()).status()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/identity/regions/NORTH", null, bob.access()).status()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/identity/regions/SOUTH", null, operator.access()).status()).isEqualTo(200);
        jdbc.update("DELETE FROM identity_user_region WHERE user_id=101 AND region_id=401");
        assertThat(call("GET", "/api/v1/identity/regions/NORTH", null, alice.access()).status()).isEqualTo(403);
    }

    @Test
    void explicitBootstrapCreatesUsableAdministratorWithoutPlainTextPassword() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE username='initial.admin'", Integer.class)).isZero();
        new BootstrapAdministrator(jdbc, passwords, transactionManager, "initial.admin", PASSWORD)
                .run(new DefaultApplicationArguments());
        Tokens administrator = login("initial.admin");
        Reply me = call("GET", "/api/v1/auth/me", null, administrator.access());
        assertThat(asStrings(me.json().get("roles"))).contains("ADMIN");
        assertThat(asStrings(me.json().get("permissions"))).contains("identity:user:manage", "region:read");
        String stored = jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE username='initial.admin'", String.class);
        assertThat(stored).doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void repeatedBootstrapDoesNotOverwriteOrElevateExistingAccount() throws Exception {
        new BootstrapAdministrator(jdbc, passwords, transactionManager, "alice", "A-new-bootstrap-password-2026!")
                .run(new DefaultApplicationArguments());
        Tokens alice = login("alice");
        Reply me = call("GET", "/api/v1/auth/me", null, alice.access());
        assertThat(asStrings(me.json().get("roles"))).containsExactly("USER");
        assertThat(asStrings(me.json().get("permissions"))).doesNotContain("identity:user:manage");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE username='alice'", String.class)).isEqualTo(passwordHash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE username='alice'", Integer.class)).isEqualTo(1);
    }

    @Test
    void bootstrapRejectsInvalidCredentialsWithoutWritingAnAccount() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM identity_user", Long.class);
        assertThatThrownBy(() -> new BootstrapAdministrator(jdbc, passwords, transactionManager, "bad user", PASSWORD)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BootstrapAdministrator(jdbc, passwords, transactionManager, "new.admin", "short")
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BootstrapAdministrator(jdbc, passwords, transactionManager, "new.admin", "密".repeat(30))
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user", Long.class)).isEqualTo(before);
    }

    private Tokens login(String username) throws Exception {
        Reply response = call("POST", "/api/v1/auth/login", Map.of("username", username, "password", PASSWORD), null);
        assertThat(response.status()).isEqualTo(200);
        return tokens(response);
    }

    private Tokens tokens(Reply response) throws Exception {
        String access = (String) response.json().get("accessToken");
        String refresh = (String) response.json().get("refreshToken");
        assertThat(access).isNotBlank();
        assertThat(refresh).isNotBlank();
        String sid = SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("sid");
        String key = "traffic:auth:session:" + sid;
        sessionKeys.add(key);
        return new Tokens(access, refresh, ((Number) response.json().get("expiresIn")).longValue(),
                Instant.parse((String) response.json().get("sessionExpiresAt")), sid, key);
    }

    private Reply refresh(String token) throws Exception { return call("POST", "/api/v1/auth/refresh", Map.of("refreshToken", token), null); }

    private Reply call(String method, String path, Map<String, ?> json, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(json)));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = response.body().startsWith("{") ? JSONObjectUtils.parse(response.body()) : Map.of();
        return new Reply(response.statusCode(), response.body(), parsed, response.headers().firstValue("X-Request-Id").orElse(""));
    }

    private String changed(String source, UnaryOperator<JWTClaimsSet.Builder> change) throws Exception {
        return sign(change.apply(new JWTClaimsSet.Builder(SignedJWT.parse(source).getJWTClaimsSet())).build(), secret);
    }

    private static String sign(JWTClaimsSet claims, String signingSecret) throws Exception {
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        signed.sign(new MACSigner(signingSecret.getBytes(StandardCharsets.UTF_8)));
        return signed.serialize();
    }

    private static List<String> asStrings(Object values) {
        assertThat(values).isInstanceOf(List.class);
        return ((List<?>) values).stream().map(Object::toString).toList();
    }

    private static <T> List<T> concurrent(int count, Callable<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                return work.call();
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private record Reply(int status, String body, Map<String, Object> json, String requestId) { }
    private record Tokens(String access, String refresh, long expiresIn, Instant sessionExpiresAt, String sid, String key) { }
}
