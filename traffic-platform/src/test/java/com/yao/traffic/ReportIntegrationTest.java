package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/** P3 gate: real HTTP, isolated MySQL, real Redis, and a private development evidence directory. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportIntegrationTest {
    private static final Path STORAGE = storageDirectory();
    private static final String PASSWORD = "Reports-test-password-2026!";
    private static final long ALICE = 5101, BOB = 5102, CHARLIE = 5103, NORTH_REVIEWER = 5104,
            SOUTH_REVIEWER = 5105, ADMIN = 5106;
    private static final Set<Long> TEST_USERS = Set.of(ALICE, BOB, CHARLIE, NORTH_REVIEWER, SOUTH_REVIEWER, ADMIN);
    private static final AtomicInteger IMAGE_SEQUENCE = new AtomicInteger();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired Flyway flyway;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> sessionKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, String> tokens = new HashMap<>();
    private final Set<Long> touchedMinutes = new HashSet<>();
    private String passwordHash;

    @DynamicPropertySource
    static void privateStorageAndConfigurableRateLimit(DynamicPropertyRegistry registry) {
        registry.add("traffic.storage.provider", () -> "local");
        registry.add("traffic.storage.local-root", STORAGE::toString);
        // Three fresh submissions make the rate boundary practical to exercise; production defaults to twenty.
        registry.add("traffic.report.rate-limit-per-minute", () -> 3);
        // Allow transport to reach the independent 5 MiB business validation.
        registry.add("spring.servlet.multipart.max-file-size", () -> "6MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "7MB");
    }

    @BeforeAll void encodePasswordOnce() { passwordHash = passwords.encode(PASSWORD); }

    @BeforeEach
    void seedOnlyTheDedicatedTestDatabase() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("traffic_platform_it");
        clearBusinessData();
        clearIdentityData();
        for (var user : List.of(Map.entry(ALICE, "p3.alice"), Map.entry(BOB, "p3.bob"),
                Map.entry(CHARLIE, "p3.charlie"), Map.entry(NORTH_REVIEWER, "p3.north-reviewer"),
                Map.entry(SOUTH_REVIEWER, "p3.south-reviewer"), Map.entry(ADMIN, "p3.admin"))) {
            jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(?,?,?,TRUE,0)",
                    user.getKey(), user.getValue(), passwordHash);
        }
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(5201,'P3_SUBMITTER'),(5202,'P3_REVIEWER'),(5203,'ADMIN')");
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES(5301,'region:read'),(5302,'campaign:manage'),"
                + "(5303,'report:submit'),(5304,'report:read'),(5305,'report:review'),(5306,'identity:user:manage')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(5101,5201),(5102,5201),(5103,5201),"
                + "(5104,5202),(5105,5202),(5106,5203)");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES"
                + "(5201,5301),(5201,5303),(5201,5304),(5202,5301),(5202,5304),(5202,5305),"
                + "(5203,5301),(5203,5302),(5203,5303),(5203,5304),(5203,5305),(5203,5306)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(5401,'NORTH','North campus'),(5402,'SOUTH','South campus')");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES"
                + "(5101,5401),(5102,5401),(5103,5402),(5104,5401),(5105,5402),(5106,5401),(5106,5402)");
        tokens.clear();
        touchedMinutes.clear();
        clearOnlyOwnedRateKeys();
    }

    @AfterEach
    void removeBusinessFixturesBeforeOtherSuitesDeleteIdentityRows() throws Exception {
        clearBusinessData();
        sessionKeys.forEach(redis::delete);
        sessionKeys.clear();
        clearOnlyOwnedRateKeys();
        clearIdentityData();
        deleteFilesUnderPrivateStorage(false);
    }

    @AfterAll void removeOnlyThisSuiteStorageDirectory() throws Exception { deleteFilesUnderPrivateStorage(true); }

    @Test
    void migratesRealMysqlWithExplicitlySeparatedReportStates() throws Exception {
        flyway.validate();
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion()).contains("1", "2", "3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        long campaign = publishedCampaign("NORTH");
        long evidence = upload("p3.alice").id();
        Reply submitted = submit(reportBody(campaign, evidence), "states-1", "p3.alice");
        assertSuccess(submitted);
        assertThat(submitted.json().get("detectionStatus")).isEqualTo("PENDING");
        assertThat(submitted.json().get("reviewStatus")).isEqualTo("WAITING_DETECTION");
        assertThat(submitted.json().get("rewardStatus")).isEqualTo("NONE");
        assertThat(count("traffic_report")).isEqualTo(1L);
        assertThat(count("detection_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM detection_task", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        String eventId = jdbc.queryForObject("SELECT event_id FROM outbox_event", String.class);
        Map<String, Object> payload = JSONObjectUtils.parse(jdbc.queryForObject("SELECT payload FROM outbox_event", String.class));
        assertThat(UUID.fromString(eventId).toString()).isEqualTo(eventId);
        assertThat(payload.get("eventId")).isEqualTo(eventId);
        assertThat(payload.get("eventType")).isEqualTo("REPORT_SUBMITTED");
        assertThat(number(payload.get("reportId"))).isEqualTo(id(submitted));
        assertThat(number(payload.get("evidenceId"))).isEqualTo(evidence);
        assertThat(number(payload.get("userId"))).isEqualTo(ALICE);
    }

    @Test
    void campaignStateTransitionsAreRestrictedAndRepeatedOperationsAreIdempotent() throws Exception {
        Reply draft = createCampaign("NORTH", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        assertSuccess(draft);
        assertThat(draft.json().get("state")).isEqualTo("DRAFT");
        long campaign = id(draft);
        for (int index = 0; index < 2; index++) {
            Reply published = call("POST", "/api/v1/campaigns/" + campaign + "/publish", null, "p3.admin", null);
            assertSuccess(published);
            assertThat(published.json().get("state")).isEqualTo("PUBLISHED");
        }
        for (int index = 0; index < 2; index++) {
            Reply closed = call("POST", "/api/v1/campaigns/" + campaign + "/close", null, "p3.admin", null);
            assertSuccess(closed);
            assertThat(closed.json().get("state")).isEqualTo("CLOSED");
        }
        assertThat(call("POST", "/api/v1/campaigns/" + campaign + "/publish", null, "p3.admin", null).status()).isEqualTo(409);
    }

    @Test
    void rejectsInvalidCampaignMoneyWindowAndUnknownRegionWithoutWritingRows() throws Exception {
        Map<String, Object> valid = campaignBody("NORTH", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        for (Map<String, Object> invalid : List.of(changed(valid, "rewardAmount", -1),
                changed(valid, "rewardAmount", "1.001"), changed(valid, "budgetAmount", "1.00"),
                changed(valid, "endsAt", Instant.now().minusSeconds(120).toString()),
                changed(valid, "regionCode", "UNKNOWN"), changed(valid, "title", ""))) {
            assertThat(call("POST", "/api/v1/campaigns", invalid, "p3.admin", null).status()).isEqualTo(400);
        }
        assertThat(count("campaign")).isZero();
    }

    @Test
    void campaignManagementRequiresPermissionAndUserListsAreRegionScoped() throws Exception {
        Map<String, Object> valid = campaignBody("NORTH", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertThat(call("POST", "/api/v1/campaigns", valid, "p3.alice", null).status()).isEqualTo(403);
        long north = publishedCampaign("NORTH");
        long south = publishedCampaign("SOUTH");
        long draft = id(createCampaign("NORTH", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)));
        assertThat(ids(call("GET", "/api/v1/campaigns", null, "p3.alice", null))).containsExactly(north);
        assertThat(ids(call("GET", "/api/v1/campaigns", null, "p3.charlie", null))).containsExactly(south);
        assertThat(ids(call("GET", "/api/v1/campaigns", null, "p3.admin", null))).containsExactlyInAnyOrder(north, south, draft);
        assertThat(call("POST", "/api/v1/campaigns/" + north + "/close", null, "p3.alice", null).status()).isEqualTo(403);
    }

    @Test
    void validImageIsHashedAndStoredWithoutTrustingFilenameOrLeakingFilesystemPaths() throws Exception {
        byte[] image = png();
        Reply uploaded = multipart(image, "../../outside.png", "image/png", "p3.alice");
        assertSuccess(uploaded);
        assertThat(uploaded.json().get("sha256")).isEqualTo(sha256(image));
        assertThat(uploaded.json().get("mediaType")).isEqualTo("image/png");
        assertThat(number(uploaded.json().get("sizeBytes"))).isEqualTo(image.length);
        assertThat(uploaded.body()).doesNotContain("object_key", "objectKey", STORAGE.toString(), "outside.png");
        Reply own = call("GET", "/api/v1/evidence/" + id(uploaded), null, "p3.alice", null);
        assertSuccess(own);
        assertThat(own.json().get("sha256")).isEqualTo(sha256(image));
        assertThat(call("GET", "/api/v1/evidence/" + id(uploaded), null, "p3.bob", null).status()).isEqualTo(403);
        try (var files = Files.walk(STORAGE)) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1L);
        }
        assertThat(Files.exists(STORAGE.getParent().resolve("outside.png"))).isFalse();
    }

    @Test
    void rejectsSpoofedImageFormatEmptyBodyAndOversizedUpload() throws Exception {
        assertThat(multipart("not-an-image".getBytes(StandardCharsets.UTF_8), "fake.png", "image/png", "p3.alice").status()).isEqualTo(400);
        assertThat(multipart(new byte[0], "empty.png", "image/png", "p3.alice").status()).isEqualTo(400);
        assertThat(multipart(gif(), "picture.gif", "image/gif", "p3.alice").status()).isEqualTo(400);
        byte[] tooLarge = Arrays.copyOf(png(), 5 * 1024 * 1024 + 1);
        assertThat(multipart(tooLarge, "large.png", "image/png", "p3.alice").status()).isEqualTo(400);
        assertThat(count("evidence")).isZero();
    }

    @Test
    void rejectsExcessiveDeclaredPixelCountBeforeAllocatingDecodedImage() throws Exception {
        byte[] declaredHuge = png();
        ByteBuffer.wrap(declaredHuge).putInt(16, 5001).putInt(20, 4000);
        CRC32 crc = new CRC32();
        crc.update(declaredHuge, 12, 17);
        ByteBuffer.wrap(declaredHuge).putInt(29, (int) crc.getValue());
        assertThat(multipart(declaredHuge, "huge.png", "image/png", "p3.alice").status()).isEqualTo(400);
        assertThat(count("evidence")).isZero();
    }

    @Test
    void requiresPublishedCampaignAndEnforcesStartAndEndBoundaries() throws Exception {
        long evidence = upload("p3.alice").id();
        long draft = id(createCampaign("NORTH", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)));
        assertThat(submit(reportBody(draft, evidence), "draft", "p3.alice").status()).isEqualTo(409);
        long future = id(createCampaign("NORTH", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200)));
        assertSuccess(call("POST", "/api/v1/campaigns/" + future + "/publish", null, "p3.admin", null));
        assertThat(submit(reportBody(future, evidence), "future", "p3.alice").status()).isEqualTo(409);
        long active = publishedCampaign("NORTH");
        jdbc.update("UPDATE campaign SET ends_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), active);
        assertThat(submit(reportBody(active, evidence), "expired", "p3.alice").status()).isEqualTo(409);
        long expiredDraft = id(createCampaign("NORTH", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        assertThat(call("POST", "/api/v1/campaigns/" + expiredDraft + "/publish", null, "p3.admin", null).status()).isEqualTo(409);
        assertThat(count("traffic_report")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void closedCampaignDoesNotAcceptNewSubmissions() throws Exception {
        long campaign = publishedCampaign("NORTH");
        long evidence = upload("p3.alice").id();
        assertSuccess(call("POST", "/api/v1/campaigns/" + campaign + "/close", null, "p3.admin", null));
        assertThat(submit(reportBody(campaign, evidence), "closed", "p3.alice").status()).isEqualTo(409);
        assertThat(count("traffic_report")).isZero();
    }

    @Test
    void verifiesRegionEvidenceOwnershipAndCaptureTimeBeforePersistingReport() throws Exception {
        long north = publishedCampaign("NORTH");
        long south = publishedCampaign("SOUTH");
        long own = upload("p3.alice").id();
        long someoneElse = upload("p3.bob").id();
        assertThat(submit(reportBody(south, own), "wrong-region", "p3.alice").status()).isEqualTo(403);
        assertThat(submit(reportBody(north, someoneElse), "wrong-owner", "p3.alice").status()).isEqualTo(403);
        assertThat(submit(changed(reportBody(north, own), "captureTime", Instant.now().plusSeconds(300).toString()),
                "future-capture", "p3.alice").status()).isEqualTo(400);
        assertThat(count("traffic_report")).isZero();
        assertThat(count("detection_task")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void requiresBoundedIdempotencyKeyAndValidReportFields() throws Exception {
        Map<String, Object> body = reportBody(publishedCampaign("NORTH"), upload("p3.alice").id());
        for (String invalid : List.of("", "contains a space", "x".repeat(129)))
            assertThat(submit(body, invalid, "p3.alice").status()).isEqualTo(400);
        assertThat(submit(body, null, "p3.alice").status()).isEqualTo(400);
        assertThat(submit(changed(body, "location", ""), "invalid-location", "p3.alice").status()).isEqualTo(400);
        assertThat(submit(changed(body, "description", ""), "invalid-description", "p3.alice").status()).isEqualTo(400);
        assertThat(count("traffic_report")).isZero();
    }

    @Test
    void identicalKeyReplaysOriginalSubmissionEvenAfterClosureWithoutConsumingMoreQuota() throws Exception {
        long campaign = publishedCampaign("NORTH");
        Map<String, Object> body = reportBody(campaign, upload("p3.alice").id());
        long report = id(submit(body, "stable-key", "p3.alice"));
        assertSuccess(call("POST", "/api/v1/campaigns/" + campaign + "/close", null, "p3.admin", null));
        for (int index = 0; index < 5; index++) {
            Reply repeated = submit(body, "stable-key", "p3.alice");
            assertSuccess(repeated);
            assertThat(id(repeated)).isEqualTo(report);
        }
        assertThat(count("traffic_report")).isEqualTo(1L);
        assertThat(count("detection_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
        assertThat(rateCount(ALICE)).isEqualTo(1L);
    }

    @Test
    void sameKeyDifferentContentAndRepeatedImageUnderAnotherKeyAreConflicts() throws Exception {
        long campaign = publishedCampaign("NORTH");
        byte[] bytes = png();
        long firstEvidence = id(multipart(bytes, "first.png", "image/png", "p3.alice"));
        Map<String, Object> original = reportBody(campaign, firstEvidence);
        assertSuccess(submit(original, "content-key", "p3.alice"));
        assertThat(submit(changed(original, "description", "different claim"), "content-key", "p3.alice").status()).isEqualTo(409);
        assertThat(submit(original, "second-key", "p3.alice").status()).isEqualTo(409);
        long reuploaded = id(multipart(bytes, "same-bytes-new-name.png", "image/png", "p3.alice"));
        assertThat(submit(reportBody(campaign, reuploaded), "third-key", "p3.alice").status()).isEqualTo(409);
        assertThat(count("traffic_report")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }

    @Test
    void idempotencyKeysRemainCaseSensitiveDespiteMysqlDefaultCollation() throws Exception {
        long campaign = publishedCampaign("NORTH");
        Map<String, Object> upperBody = reportBody(campaign, upload("p3.alice").id());
        Map<String, Object> lowerBody = changed(reportBody(campaign, upload("p3.alice").id()),
                "description", "A different valid line of evidence");
        long upperReport = id(submit(upperBody, "CaseSensitiveKey", "p3.alice"));
        long lowerReport = id(submit(lowerBody, "casesensitivekey", "p3.alice"));
        assertThat(lowerReport).isNotEqualTo(upperReport);
        assertThat(id(submit(upperBody, "CaseSensitiveKey", "p3.alice"))).isEqualTo(upperReport);
        assertThat(count("traffic_report")).isEqualTo(2L);
        assertThat(count("detection_task")).isEqualTo(2L);
        assertThat(count("outbox_event")).isEqualTo(2L);
        assertThat(rateCount(ALICE)).isEqualTo(2L);
    }

    @Test
    void twentyConcurrentIdenticalSubmissionsProduceOneReportTaskAndEvent() throws Exception {
        long campaign = publishedCampaign("NORTH");
        Map<String, Object> body = reportBody(campaign, upload("p3.alice").id());
        bearer("p3.alice"); // Login once before the parallel region; the tested race is submission, not login.
        List<Reply> replies = concurrent(20, () -> submit(body, "parallel-key", "p3.alice"));
        assertThat(replies).allSatisfy(ReportIntegrationTest::assertSuccess);
        assertThat(replies.stream().map(ReportIntegrationTest::id).distinct().count()).isEqualTo(1L);
        assertThat(count("traffic_report")).isEqualTo(1L);
        assertThat(count("detection_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
        assertThat(rateCount(ALICE)).isEqualTo(1L);
    }

    @Test
    void outboxDatabaseFailureRollsBackReportAndTaskAndTheSameKeyCanRecover() throws Exception {
        long campaign = publishedCampaign("NORTH");
        Map<String, Object> body = reportBody(campaign, upload("p3.alice").id());
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT p3_test_reject_outbox CHECK (1=0)");
        try {
            Reply failed = submit(body, "recoverable-key", "p3.alice");
            assertThat(failed.status()).isBetween(500, 599);
            assertThat(count("traffic_report")).isZero();
            assertThat(count("detection_task")).isZero();
            assertThat(count("outbox_event")).isZero();
            assertThat(count("evidence")).isEqualTo(1L); // Upload is a prior, separate operation.
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CHECK p3_test_reject_outbox");
        }
        assertSuccess(submit(body, "recoverable-key", "p3.alice"));
        assertThat(count("traffic_report")).isEqualTo(1L);
        assertThat(count("detection_task")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }

    @Test
    void rateLimitUsesRealRedisAndRejectsOnlyNewSubmissionsPastConfiguredBoundary() throws Exception {
        long campaign = publishedCampaign("NORTH");
        for (int index = 0; index < 3; index++)
            assertSuccess(submit(reportBody(campaign, upload("p3.alice").id()), "rate-" + index, "p3.alice"));
        Reply fourth = submit(reportBody(campaign, upload("p3.alice").id()), "rate-fourth", "p3.alice");
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(count("traffic_report")).isEqualTo(3L);
        assertThat(count("outbox_event")).isEqualTo(3L);
        assertThat(rateCount(ALICE)).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void reportListsAndDetailsEnforceOwnershipAndReviewerRegionEvenWithClientIdentityParameters() throws Exception {
        long north = publishedCampaign("NORTH");
        long south = publishedCampaign("SOUTH");
        long alice = id(submit(reportBody(north, upload("p3.alice").id()), "alice-private", "p3.alice"));
        long bob = id(submit(reportBody(north, upload("p3.bob").id()), "bob-private", "p3.bob"));
        long charlie = id(submit(reportBody(south, upload("p3.charlie").id()), "charlie-private", "p3.charlie"));
        assertThat(ids(call("GET", "/api/v1/reports?userId=" + BOB, null, "p3.alice", null))).containsExactly(alice);
        assertThat(ids(call("GET", "/api/v1/reports", null, "p3.north-reviewer", null))).containsExactlyInAnyOrder(alice, bob);
        assertThat(ids(call("GET", "/api/v1/reports", null, "p3.south-reviewer", null))).containsExactly(charlie);
        assertThat(call("GET", "/api/v1/reports/" + bob, null, "p3.alice", null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/reports/" + alice, null, "p3.north-reviewer", null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/reports/" + charlie, null, "p3.north-reviewer", null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/reports/" + alice, null, "p3.charlie", null).status()).isEqualTo(403);
    }

    @Test
    void storedRewardIsSnapshotAndPaginationCannotBypassScopeOrSizeLimits() throws Exception {
        long campaign = publishedCampaign("NORTH");
        long report = id(submit(reportBody(campaign, upload("p3.alice").id()), "snapshot", "p3.alice"));
        jdbc.update("UPDATE campaign SET reward_amount=9.99 WHERE id=?", campaign);
        Reply details = call("GET", "/api/v1/reports/" + report, null, "p3.alice", null);
        assertSuccess(details);
        assertThat(new java.math.BigDecimal(details.json().get("rewardAmount").toString())).isEqualByComparingTo("3.50");
        for (String path : List.of("/api/v1/reports?size=101", "/api/v1/reports?page=0", "/api/v1/campaigns?size=0"))
            assertThat(call("GET", path, null, "p3.alice", null).status()).isEqualTo(400);
        Reply page = call("GET", "/api/v1/reports?page=1&size=1", null, "p3.alice", null);
        assertThat(ids(page)).containsExactly(report);
        assertThat(number(page.json().get("page"))).isEqualTo(1L);
        assertThat(number(page.json().get("size"))).isEqualTo(1L);
    }

    private Reply createCampaign(String region, Instant start, Instant end) throws Exception {
        return call("POST", "/api/v1/campaigns", campaignBody(region, start, end), "p3.admin", null);
    }

    private long publishedCampaign(String region) throws Exception {
        long id = id(createCampaign(region, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)));
        assertSuccess(call("POST", "/api/v1/campaigns/" + id + "/publish", null, "p3.admin", null));
        return id;
    }

    private static Map<String, Object> campaignBody(String region, Instant start, Instant end) {
        return Map.of("title", "Parking evidence campaign", "regionCode", region, "startsAt", start.toString(),
                "endsAt", end.toString(), "rewardAmount", "3.50", "budgetAmount", "100.00");
    }

    private static Map<String, Object> reportBody(long campaign, long evidence) {
        return Map.of("campaignId", campaign, "evidenceId", evidence, "location", "North gate",
                "description", "Vehicle obstructing the marked entrance", "captureTime", Instant.now().minusSeconds(120).toString());
    }

    private Evidence upload(String user) throws Exception {
        Reply uploaded = multipart(png(), "evidence.png", "image/png", user);
        assertSuccess(uploaded);
        return new Evidence(id(uploaded), (String) uploaded.json().get("sha256"));
    }

    private Reply submit(Map<String, ?> body, String key, String user) throws Exception {
        synchronized (touchedMinutes) { touchedMinutes.add(Instant.now().getEpochSecond() / 60); }
        return call("POST", "/api/v1/reports", body, user, key);
    }

    private Reply multipart(byte[] content, String filename, String mediaType, String user) throws Exception {
        String boundary = "TrafficP3" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/evidence")).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + bearer(user))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return reply(client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private Reply call(String method, String path, Map<String, ?> json, String username, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json");
        if (username != null) request.header("Authorization", "Bearer " + bearer(username));
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(json)));
        return reply(client.send(request.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private String bearer(String username) throws Exception {
        if (tokens.containsKey(username)) return tokens.get(username);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username", username, "password", PASSWORD)))).build();
        Reply response = reply(client.send(request, HttpResponse.BodyHandlers.ofString()));
        assertSuccess(response);
        String token = (String) response.json().get("accessToken");
        assertThat(token).isNotBlank();
        sessionKeys.add("traffic:auth:session:" + SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));
        tokens.put(username, token);
        return token;
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private static Reply reply(HttpResponse<String> response) throws Exception {
        Map<String, Object> json = response.body().startsWith("{") ? JSONObjectUtils.parse(response.body()) : Map.of();
        return new Reply(response.statusCode(), response.body(), json);
    }

    private static void assertSuccess(Reply reply) {
        assertThat(reply.status()).withFailMessage("Expected HTTP 200 but got %s: %s", reply.status(), reply.body()).isEqualTo(200);
    }

    private static long id(Reply reply) { assertSuccess(reply); return number(reply.json().get("id")); }
    private static long number(Object value) { assertThat(value).isInstanceOf(Number.class); return ((Number) value).longValue(); }

    private static List<Long> ids(Reply reply) {
        assertSuccess(reply);
        assertThat(reply.json().get("items")).isInstanceOf(List.class);
        return ((List<?>) reply.json().get("items")).stream().map(item -> number(((Map<?, ?>) item).get("id"))).toList();
    }

    private static Map<String, Object> changed(Map<String, ?> original, String name, Object value) {
        Map<String, Object> changed = new HashMap<>(original);
        changed.put(name, value);
        return changed;
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private void clearBusinessData() {
        for (String table : List.of("outbox_event", "detection_task", "traffic_report", "evidence", "campaign")) jdbc.update("DELETE FROM " + table);
    }

    private void clearIdentityData() {
        for (String table : List.of("identity_user_region", "identity_user_role", "identity_role_permission", "identity_permission",
                "identity_role", "identity_user", "identity_region")) jdbc.update("DELETE FROM " + table);
    }

    private void clearOnlyOwnedRateKeys() {
        Set<Long> minutes = new HashSet<>(touchedMinutes);
        long now = Instant.now().getEpochSecond() / 60;
        for (long delta = -2; delta <= 2; delta++) minutes.add(now + delta);
        for (long user : TEST_USERS) for (long minute : minutes) redis.delete(rateKey(user, minute));
    }

    private long rateCount(long userId) {
        long total = 0;
        Set<Long> minutes = new HashSet<>(touchedMinutes);
        minutes.add(Instant.now().getEpochSecond() / 60);
        for (long minute : minutes) {
            String value = redis.opsForValue().get(rateKey(userId, minute));
            if (value != null) total += Long.parseLong(value);
        }
        return total;
    }

    private static String rateKey(long userId, long minute) { return "traffic:report:rate:" + userId + ":" + minute; }

    private static byte[] png() throws Exception {
        int seed = IMAGE_SEQUENCE.incrementAndGet();
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, ((x * 17 + seed * 23) & 255) << 16 | ((y * 13 + seed * 7) & 255) << 8 | (seed & 255));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] gif() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "gif", output)).isTrue();
        return output.toByteArray();
    }

    private static Path storageDirectory() {
        try {
            Path target = Path.of("target").toAbsolutePath().normalize();
            Files.createDirectories(target);
            return Files.createTempDirectory(target, "p3-evidence-").toRealPath();
        } catch (Exception exception) { throw new IllegalStateException("Cannot allocate isolated evidence directory", exception); }
    }

    private static void deleteFilesUnderPrivateStorage(boolean includeRoot) throws Exception {
        Path target = STORAGE.getParent().toAbsolutePath().normalize();
        Path checked = STORAGE.toAbsolutePath().normalize();
        if (!checked.startsWith(target) || !checked.getFileName().toString().startsWith("p3-evidence-"))
            throw new IllegalStateException("Refusing to delete an unverified evidence directory");
        if (!Files.exists(checked)) return;
        try (var files = Files.walk(checked)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                if (includeRoot || !path.equals(checked)) Files.deleteIfExists(path);
        }
    }

    private static <T> List<T> concurrent(int count, Callable<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent submission start timed out");
                return work.call();
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(35, TimeUnit.SECONDS));
            return results;
        }
    }

    private record Reply(int status, String body, Map<String, Object> json) { }
    private record Evidence(long id, String sha256) { }
}
