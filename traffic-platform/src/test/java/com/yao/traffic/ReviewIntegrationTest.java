package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.messaging.OutboxPublisher;
import com.yao.traffic.review.ApprovalPreparationPort;
import com.yao.traffic.review.PendingApprovalPreparation;
import com.yao.traffic.review.ReviewCompletionConsumer;
import com.yao.traffic.review.ReviewEvent;
import com.yao.traffic.review.ReviewTasks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** P5 gate: actual HTTP, MySQL, Redis and the existing completed queue topology. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewIntegrationTest {
    private static final String NAMESPACE = "traffic-p5-it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String COMPLETED = NAMESPACE + ".completed", DEAD = NAMESPACE + ".dead",
            DECIDED = NAMESPACE + ".review.decided", DETECTION = NAMESPACE + ".detection",
            EXCHANGE = NAMESPACE + ".events";
    private static final String CONSUMER = "review-task-creation-v1", PASSWORD = "P5-workflow-password-2026!";
    private static final Path STORAGE = privateStorage();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean EvidenceStorage storage;
    @Autowired OutboxPublisher publisher;
    @Autowired ReviewTasks reviewTasks;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoSpyBean ReviewCompletionConsumer completionConsumer;
    @MockitoSpyBean PendingApprovalPreparation approvalPreparation;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private String passwordHash;

    @DynamicPropertySource
    static void isolateTransportAndTiming(DynamicPropertyRegistry properties) {
        properties.add("traffic.messaging.enabled", () -> true);
        properties.add("traffic.messaging.namespace", () -> NAMESPACE);
        properties.add("traffic.messaging.listener-enabled", () -> false);
        properties.add("traffic.messaging.polling-enabled", () -> false);
        properties.add("traffic.messaging.confirm-timeout-ms", () -> 1000);
        properties.add("traffic.review.listener-enabled", () -> false);
        properties.add("traffic.review.polling-enabled", () -> false);
        properties.add("traffic.review.lease-seconds", () -> 5);
        properties.add("traffic.review.max-transfers", () -> 2);
        properties.add("traffic.review.dependency-backoff-ms", () -> 80);
        properties.add("traffic.storage.provider", () -> "local");
        properties.add("traffic.storage.local-root", STORAGE::toString);
        properties.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        properties.add("spring.rabbitmq.publisher-returns", () -> true);
        properties.add("spring.rabbitmq.template.mandatory", () -> true);
        properties.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        properties.add("spring.rabbitmq.listener.simple.concurrency", () -> 1);
        properties.add("spring.rabbitmq.listener.simple.max-concurrency", () -> 1);
        properties.add("management.health.rabbit.enabled", () -> true);
    }

    @BeforeAll void encodeFixturePassword() { passwordHash = passwords.encode(PASSWORD); }

    @BeforeEach
    void prepareOnlyPrivateTestResources() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("traffic_platform_it");
        stopListeners();
        awaitBrokerReady();
        purgeOwnedQueues();
        removeOnlyOwnedFaultInjectionArtifacts();
        clearDatabase();
        for (var user : List.of(Map.entry(8101L, "p5.owner"), Map.entry(8102L, "p5.primary"),
                Map.entry(8103L, "p5.rechecker"), Map.entry(8104L, "p5.outsider"),
                Map.entry(8105L, "p5.manager"), Map.entry(8106L, "p5.plain"), Map.entry(8107L, "p5.disabled")))
            jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(?,?,?,TRUE,0)",
                    user.getKey(), user.getValue(), passwordHash);
        jdbc.update("UPDATE identity_user SET enabled=FALSE WHERE id=8107");
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(8201,'P5_PRIMARY'),(8202,'P5_ADVANCED'),(8203,'P5_MANAGER'),(8204,'P5_PLAIN')");
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES(8301,'report:read'),(8302,'report:review'),"
                + "(8303,'review:claim'),(8304,'review:decide'),(8305,'review:recheck'),(8306,'review:manage')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(8101,8201),(8102,8201),(8103,8202),"
                + "(8104,8203),(8105,8203),(8106,8204),(8107,8202)");
        for (long role : List.of(8201L, 8202L, 8203L)) for (long permission : List.of(8301L, 8302L, 8303L, 8304L))
            jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(?,?)", role, permission);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8202,8305),(8203,8305),(8203,8306),(8204,8301)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(8401,'NORTH','North campus'),(8402,'SOUTH','South campus')");
        for (long user : List.of(8101L, 8102L, 8103L, 8105L, 8106L, 8107L))
            jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(?,8401)", user);
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(8104,8402)");
        jdbc.update("INSERT INTO campaign(id,title,region_code,starts_at,ends_at,reward_amount,budget_amount,state,created_by)"
                + " VALUES(8501,'P5 review campaign','NORTH',?,?,3.50,1000.00,'PUBLISHED',8105)",
                Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().plusSeconds(3600)));
        tokens.clear();
    }

    @AfterEach
    void removeDependentReviewRowsBeforePrecedingSuitesClearIdentity() throws Exception {
        stopListeners();
        clearDatabase();
        sessions.forEach(redis::delete);
        sessions.clear();
        purgeOwnedQueues();
        removeStorage(false);
    }

    @AfterAll
    void removeOnlyThePrivateBrokerNamespace() throws Exception {
        stopListeners();
        for (String queue : List.of(COMPLETED, DEAD, DECIDED, DETECTION)) admin.deleteQueue(queue);
        admin.deleteExchange(EXCHANGE);
        admin.deleteExchange(NAMESPACE + ".dead.exchange");
        removeStorage(true);
    }

    @Test
    void realCompletedEventCreatesPrimaryTaskInboxAndCreatedAuditInOneTransaction() {
        Fixture item = completed(false);
        startReviewListener();
        publisher.publishPending();
        await(() -> workCount(item.reportId()) == 1);
        String task = workId(item);
        assertThat(text("SELECT stage FROM review_task WHERE id=?", task)).isEqualTo("PRIMARY");
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("READY");
        assertThat(number("SELECT source_detection_version FROM review_task WHERE id=?", task)).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=? AND event_id=?", CONSUMER, item.eventId())).isEqualTo(1L);
        assertThat(auditCount(task, "CREATED")).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM review_approval_intent")).isZero();
    }

    @Test
    void highRiskStartsAtRecheckAndCannotBeClaimedByAnOrdinaryPrimaryReviewer() throws Exception {
        Fixture item = completed(true);
        accept(item);
        String task = workId(item);
        assertThat(text("SELECT stage FROM review_task WHERE id=?", task)).isEqualTo("RECHECK");
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", 0), "p5.primary").status()).isEqualTo(403);
        Claim claim = claim(task, 0, "p5.rechecker");
        assertThat(claim.stage()).isEqualTo("RECHECK");
        assertThat(jdbc.queryForObject("SELECT primary_reviewer_id FROM review_task WHERE id=?", Long.class, task)).isNull();
    }

    @Test
    void duplicateMessagesAndAnotherValidCompletionSourceStillCreateOneWorkItem() {
        Fixture item = completed(false);
        startReviewListener();
        publisher.publishPending();
        for (int index = 0; index < 4; index++) sendRaw(item.payload());
        await(() -> workCount(item.reportId()) == 1 && queueDepth(COMPLETED) == 0);
        String secondId = UUID.randomUUID().toString();
        Map<String, Object> secondPayload = new HashMap<>(parse(item.payload()));
        secondPayload.put("eventId", secondId);
        String body = JSONObjectUtils.toJSONString(secondPayload);
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count)"
                + " VALUES(?,'REPORT',?,'DETECTION_COMPLETED',?,'SENT',0)", secondId, item.reportId(), body);
        sendRaw(body);
        await(() -> number("SELECT COUNT(*) FROM message_inbox WHERE aggregate_id=? AND consumer_name=?", item.reportId(), CONSUMER) == 2);
        assertThat(workCount(item.reportId())).isEqualTo(1L);
        assertThat(auditCount(workId(item), "CREATED")).isEqualTo(1L);
    }

    @Test
    void commitBeforeAckThenChannelLossRedeliversWithoutDuplicateTaskOrInbox() throws Exception {
        Fixture item = completed(false);
        publisher.publishPending();
        try (var connection = rawFactory().newConnection()) {
            Channel first = connection.createChannel();
            GetResponse delivered = first.basicGet(COMPLETED, false);
            assertThat(delivered).isNotNull();
            assertThat(delivered.getProps().getMessageId()).isEqualTo(item.eventId());
            assertThat(completionConsumer.process(item.event()).toString()).isEqualTo("ACK");
            first.close(); // The transaction committed, but the broker never received the acknowledgement.
            try (Channel replacement = connection.createChannel()) {
                AtomicReference<GetResponse> replay = new AtomicReference<>();
                await(() -> {
                    try { replay.set(replacement.basicGet(COMPLETED, false)); return replay.get() != null; }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                });
                assertThat(replay.get().getEnvelope().isRedeliver()).isTrue();
                assertThat(completionConsumer.process(item.event()).toString()).isEqualTo("ACK");
                replacement.basicAck(replay.get().getEnvelope().getDeliveryTag(), false);
            }
        }
        assertThat(workCount(item.reportId())).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=?", CONSUMER)).isEqualTo(1L);
        assertThat(auditCount(workId(item), "CREATED")).isEqualTo(1L);
    }

    @Test
    void poisonMessagesUseConfirmedQuarantineWithoutChangingTheExistingCompletedQueueArguments() {
        Fixture item = completed(false);
        startReviewListener();
        sendRaw("not-json");
        Map<String, Object> unsupported = new HashMap<>(parse(item.payload()));
        unsupported.put("schemaVersion", 99);
        sendRaw(JSONObjectUtils.toJSONString(unsupported));
        Map<String, Object> mismatched = new HashMap<>(parse(item.payload()));
        mismatched.put("evidenceId", item.evidenceId() + 1000);
        sendRaw(JSONObjectUtils.toJSONString(mismatched));
        await(() -> queueDepth(DEAD) == 3);
        assertThat(workCount(item.reportId())).isZero();
        assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=?", CONSUMER)).isZero();
        assertThat(queueDepth(COMPLETED)).isZero();
        // P4's completed queue has no DLX. P5 must explicitly confirm quarantine before rejecting.
        assertThat(rabbit.receive(DEAD, 2000)).isNotNull();
    }

    @Test
    void realMysqlWorkItemFailureRollsBackInboxAndRedeliveryRecoversAfterConstraintRemoval() throws Exception {
        Fixture item = completed(false);
        CountDownLatch failedTransaction = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        doAnswer(invocation -> {
            deliveries.incrementAndGet();
            try { return invocation.callRealMethod(); }
            catch (RuntimeException failure) { failedTransaction.countDown(); throw failure; }
        }).when(completionConsumer).process(any(ReviewEvent.class));
        jdbc.execute("ALTER TABLE review_task ADD CONSTRAINT p5_test_reject_creation CHECK (1=0)");
        try {
            startReviewListener();
            publisher.publishPending();
            assertThat(failedTransaction.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(workCount(item.reportId())).isZero();
            assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=?", CONSUMER)).isZero();
            assertThat(number("SELECT COUNT(*) FROM review_audit")).isZero();
        } finally { jdbc.execute("ALTER TABLE review_task DROP CHECK p5_test_reject_creation"); }
        await(() -> workCount(item.reportId()) == 1);
        assertThat(deliveries.get()).isGreaterThanOrEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=?", CONSUMER)).isEqualTo(1L);
        assertThat(auditCount(workId(item), "CREATED")).isEqualTo(1L);
    }

    @Test
    void sentCompletionWithLostBrokerWakeupIsRecoveredOnceFromTrustedDatabaseSources() {
        Fixture item = completed(false);
        jdbc.update("UPDATE outbox_event SET status='SENT',published_at=CURRENT_TIMESTAMP WHERE event_id=?", item.eventId());
        assertThat(completionConsumer.recoverMissing()).isGreaterThanOrEqualTo(1);
        assertThat(workCount(item.reportId())).isEqualTo(1L);
        completionConsumer.recoverMissing();
        assertThat(workCount(item.reportId())).isEqualTo(1L);
        assertThat(auditCount(workId(item), "CREATED")).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM message_inbox WHERE event_id=? AND consumer_name=?", item.eventId(), CONSUMER)).isEqualTo(1L);
    }

    @Test
    void missingSignalRecoveryDoesNotBypassAnUnsentOrDeadCompletionEvent() {
        Fixture pending = completed(false);
        Fixture dead = completed(true);
        jdbc.update("UPDATE outbox_event SET status='DEAD' WHERE event_id=?", dead.eventId());
        completionConsumer.recoverMissing();
        assertThat(workCount(pending.reportId())).isZero();
        assertThat(workCount(dead.reportId())).isZero();
    }

    @Test
    void twoReviewersClaimingOneVersionProduceOneWinnerAndOneConflict() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        bearer("p5.primary"); bearer("p5.rechecker");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Reply> first = executor.submit(() -> { start.await(); return api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", 0), "p5.primary"); });
            Future<Reply> second = executor.submit(() -> { start.await(); return api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", 0), "p5.rechecker"); });
            start.countDown();
            List<Reply> replies = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(replies.stream().filter(reply -> reply.status() == 200).count()).isEqualTo(1L);
            assertThat(replies.stream().filter(reply -> reply.status() == 409).count()).isEqualTo(1L);
            Map<String, Object> winner = replies.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow().json();
            assertThat(winner.get("leaseToken")).isInstanceOf(String.class);
            assertThat(((String) winner.get("leaseToken"))).isNotBlank();
            assertThat(num(winner.get("version"))).isEqualTo(1L);
        }
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("CLAIMED");
        assertThat(auditCount(task, "CLAIMED")).isEqualTo(1L);
    }

    @Test
    void ordinaryUsersOutsidersAndTheReportOwnerCannotClaimEvenWithAValidVersion() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        for (String user : List.of("p5.plain", "p5.outsider", "p5.owner"))
            assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", 0), user).status()).isEqualTo(403);
        assertThat(api("GET", taskPath(task), null, "p5.plain").status()).isEqualTo(403);
        assertThat(api("GET", taskPath(task), null, "p5.outsider").status()).isEqualTo(403);
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("READY");
    }

    @Test
    void decisionRequiresCurrentAssigneeVersionTokenAndAReason() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claimed = claim(workId(item), 0, "p5.primary");
        Map<String, Object> request = decision(claimed, "REJECT", "Not sufficient evidence");
        assertThat(api("POST", taskPath(claimed.id()) + "/decisions", changed(request, "version", claimed.version() + 1), "p5.primary").status()).isEqualTo(409);
        assertThat(api("POST", taskPath(claimed.id()) + "/decisions", changed(request, "leaseToken", UUID.randomUUID().toString()), "p5.primary").status()).isEqualTo(409);
        assertThat(api("POST", taskPath(claimed.id()) + "/decisions", request, "p5.rechecker").status()).isIn(403, 409);
        assertThat(api("POST", taskPath(claimed.id()) + "/decisions", changed(request, "reason", ""), "p5.primary").status()).isEqualTo(400);
        assertThat(number("SELECT COUNT(*) FROM review_decision")).isZero();
        assertThat(text("SELECT status FROM review_task WHERE id=?", claimed.id())).isEqualTo("CLAIMED");
    }

    @Test
    void expiredLeaseAndOldClaimCannotOverwriteANewerReviewerDecision() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        Claim old = claim(task, 0, "p5.primary");
        expire(task);
        assertThat(api("POST", taskPath(task) + "/decisions", decision(old, "APPROVE", "Late response"), "p5.primary").status()).isEqualTo(409);
        assertThat(reviewTasks.recoverExpired()).isGreaterThanOrEqualTo(1);
        Claim current = claim(task, version(task), "p5.rechecker");
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(api("POST", taskPath(task) + "/decisions", decision(old, "APPROVE", "Old token"), "p5.primary").status()).isEqualTo(409);
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(current, "REJECT", "Current reviewer decision"), "p5.rechecker"));
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("REJECTED");
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", task)).isEqualTo(1L);
    }

    @Test
    void boundedLeaseRecoveryRequiresExplicitManagerActionAfterTransferLimit() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        for (int attempt = 0; attempt < 2; attempt++) {
            claim(task, version(task), "p5.primary");
            expire(task);
            assertThat(reviewTasks.recoverExpired()).isGreaterThanOrEqualTo(1);
        }
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("MANUAL_REQUIRED");
        assertThat(number("SELECT transfer_count FROM review_task WHERE id=?", task)).isEqualTo(2L);
        assertThat(reviewTasks.recoverExpired()).isZero();
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", version(task)), "p5.primary").status()).isEqualTo(409);
        assertSuccess(api("POST", taskPath(task) + "/reassign", Map.of("expectedVersion", version(task), "targetReviewerId", 8103, "reason", "Manual handling of repeated timeouts"), "p5.manager"));
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("READY");
        assertThat(number("SELECT transfer_count FROM review_task WHERE id=?", task)).isEqualTo(2L);
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", version(task)), "p5.primary").status()).isIn(403, 409);
        assertThat(claim(task, version(task), "p5.rechecker").assignee()).isEqualTo(8103L);
    }

    @Test
    void requestingRecheckPreservesPrimaryHistoryAndPreventsTheSamePersonFromRechecking() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        Claim primary = claim(task, 0, "p5.primary");
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(primary, "REQUEST_RECHECK", "Image needs an independent second review"), "p5.primary"));
        assertThat(text("SELECT stage FROM review_task WHERE id=?", task)).isEqualTo("RECHECK");
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("READY");
        assertThat(number("SELECT primary_reviewer_id FROM review_task WHERE id=?", task)).isEqualTo(8102L);
        // Granting the role later cannot bypass separation of duties for this particular work item.
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8201,8305)");
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", version(task)), "p5.primary").status()).isEqualTo(403);
        Claim recheck = claim(task, version(task), "p5.rechecker");
        assertThat(api("POST", taskPath(task) + "/decisions", decision(recheck, "REQUEST_RECHECK", "Cannot escalate indefinitely"), "p5.rechecker").status()).isEqualTo(409);
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(recheck, "APPROVE", "Independent review confirms the evidence"), "p5.rechecker"));
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", task)).isEqualTo(2L);
        assertThat(number("SELECT COUNT(*) FROM review_approval_intent WHERE report_id=?", item.reportId())).isEqualTo(1L);
    }

    @Test
    void terminalDecisionReplayIsExactAndCannotReverseOrImpersonateTheOriginalReviewer() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claim = claim(workId(item), 0, "p5.primary");
        Map<String, Object> request = decision(claim, "APPROVE", "Confirmed parking obstruction");
        assertSuccess(api("POST", taskPath(claim.id()) + "/decisions", request, "p5.primary"));
        long audit = number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claim.id());
        long events = number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId());
        assertSuccess(api("POST", taskPath(claim.id()) + "/decisions", request, "p5.primary"));
        assertThat(api("POST", taskPath(claim.id()) + "/decisions", changed(request, "reason", "Different reason"), "p5.primary").status()).isEqualTo(409);
        assertThat(api("POST", taskPath(claim.id()) + "/decisions", changed(request, "decision", "REJECT"), "p5.primary").status()).isEqualTo(409);
        assertThat(api("POST", taskPath(claim.id()) + "/decisions", changed(request, "leaseToken", UUID.randomUUID().toString()), "p5.primary").status()).isEqualTo(409);
        assertThat(api("POST", taskPath(claim.id()) + "/decisions", request, "p5.rechecker").status()).isIn(403, 409);
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", claim.id())).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claim.id())).isEqualTo(audit);
        assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId())).isEqualTo(events);
        assertThat(number("SELECT COUNT(*) FROM review_approval_intent WHERE report_id=?", item.reportId())).isEqualTo(1L);
    }

    @Test
    void reassignmentChecksManagerScopeEligibleTargetAndPreviousVersion() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim original = claim(workId(item), 0, "p5.primary");
        Map<String, Object> command = Map.of("expectedVersion", version(original.id()), "targetReviewerId", 8103, "reason", "Reviewer unavailable");
        assertThat(api("POST", taskPath(original.id()) + "/reassign", command, "p5.primary").status()).isEqualTo(403);
        assertThat(api("POST", taskPath(original.id()) + "/reassign", command, "p5.outsider").status()).isEqualTo(403);
        assertThat(api("POST", taskPath(original.id()) + "/reassign", changed(command, "expectedVersion", 0), "p5.manager").status()).isEqualTo(409);
        for (long target : List.of(8101L, 8104L, 8107L))
            assertThat(api("POST", taskPath(original.id()) + "/reassign", changed(command, "targetReviewerId", target), "p5.manager").status()).isIn(400, 403);
        Reply assigned = api("POST", taskPath(original.id()) + "/reassign", command, "p5.manager");
        assertSuccess(assigned);
        assertThat(assigned.body()).doesNotContain("leaseToken", original.leaseToken());
        assertThat(api("POST", taskPath(original.id()) + "/decisions", decision(original, "APPROVE", "Old assignment"), "p5.primary").status()).isEqualTo(409);
        assertThat(claim(original.id(), version(original.id()), "p5.rechecker").assignee()).isEqualTo(8103L);
    }

    @Test
    void listsDetailsAndAuditsRespectScopePaginationAndNeverDiscloseClaimTokens() throws Exception {
        Fixture low = completed(false), high = completed(true);
        accept(low); accept(high);
        Claim owned = claim(workId(low), 0, "p5.primary");
        Reply details = api("GET", taskPath(owned.id()), null, "p5.rechecker");
        assertSuccess(details);
        assertThat(details.body()).doesNotContain("leaseToken", "lease_token", owned.leaseToken(), "leaseTokenHash", "password");
        Reply list = api("GET", "/api/v1/review-tasks?userId=8102&page=1&size=20", null, "p5.rechecker");
        assertSuccess(list);
        assertThat(list.body()).doesNotContain("leaseToken", owned.leaseToken(), "lease_token_hash");
        assertThat(listIds(list)).contains(owned.id(), workId(high));
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=READY", null, "p5.rechecker"))).containsExactly(workId(high));
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=CLAIMED&userId=8102", null, "p5.rechecker"))).isEmpty();
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=CLAIMED", null, "p5.primary"))).containsExactly(owned.id());
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=READY&stage=RECHECK", null, "p5.primary"))).isEmpty();
        assertThat(listIds(api("GET", "/api/v1/review-tasks", null, "p5.outsider"))).isEmpty();
        Reply audit = api("GET", taskPath(owned.id()) + "/audit", null, "p5.rechecker");
        assertSuccess(audit);
        assertThat(audit.body()).doesNotContain("leaseToken", owned.leaseToken(), "lease_token_hash");
        assertThat(api("GET", "/api/v1/review-tasks?size=101", null, "p5.rechecker").status()).isEqualTo(400);
        assertThat(api("GET", taskPath(owned.id()) + "/audit?page=0", null, "p5.rechecker").status()).isEqualTo(400);
    }

    @Test
    void regionalManageOnlyPermissionAllowsOperationalReadingButNeverClaimingOrDeciding() throws Exception {
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(8108,'p5.manage-only',?,TRUE,0)", passwordHash);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(8205,'P5_MANAGE_ONLY')");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8205,8306)");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(8108,8205)");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(8108,8401)");
        Fixture low = completed(false), high = completed(true);
        accept(low); accept(high);
        Claim assigned = claim(workId(low), 0, "p5.primary");
        assertThat(listIds(api("GET", "/api/v1/review-tasks", null, "p5.manage-only")))
                .containsExactlyInAnyOrder(assigned.id(), workId(high));
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=CLAIMED", null, "p5.manage-only"))).containsExactly(assigned.id());
        assertThat(api("GET", taskPath(assigned.id()), null, "p5.manage-only").status()).isEqualTo(200);
        assertThat(api("POST", taskPath(workId(high)) + "/claim", Map.of("expectedVersion", 0), "p5.manage-only").status()).isEqualTo(403);
        assertThat(api("POST", taskPath(assigned.id()) + "/decisions", decision(assigned, "APPROVE", "Read permission cannot submit decisions"), "p5.manage-only").status()).isEqualTo(403);
        assertThat(number("SELECT COUNT(*) FROM review_decision")).isZero();
        jdbc.update("DELETE FROM identity_user_region WHERE user_id=8108");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(8108,8402)");
        assertThat(listIds(api("GET", "/api/v1/review-tasks", null, "p5.manage-only"))).isEmpty();
        assertThat(api("GET", taskPath(assigned.id()), null, "p5.manage-only").status()).isEqualTo(403);
    }

    @Test
    void previousPrimaryReviewerRetainsReadOnlyOverviewButCannotClaimTheIndependentRecheck() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item);
        Claim primary = claim(task, 0, "p5.primary");
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(primary, "REQUEST_RECHECK", "Independent review is necessary"), "p5.primary"));
        assertThat(listIds(api("GET", "/api/v1/review-tasks", null, "p5.primary"))).containsExactly(task);
        assertThat(api("GET", taskPath(task), null, "p5.primary").status()).isEqualTo(200);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8201,8305)");
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=READY&stage=RECHECK", null, "p5.primary"))).isEmpty();
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", version(task)), "p5.primary").status()).isEqualTo(403);
        Claim independent = claim(task, version(task), "p5.rechecker");
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(independent, "REJECT", "Independent final assessment"), "p5.rechecker"));
        assertThat(listIds(api("GET", "/api/v1/review-tasks?status=REJECTED", null, "p5.primary"))).containsExactly(task);
        Reply audit = api("GET", taskPath(task) + "/audit", null, "p5.primary");
        assertSuccess(audit);
        assertThat(audit.body()).doesNotContain("leaseToken", primary.leaseToken(), independent.leaseToken());
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", task)).isEqualTo(2L);
    }

    @Test
    void approvalOnlyCreatesOneIntentAndDoesNotChangeMoneyOrBudgetInP5() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claim = claim(workId(item), 0, "p5.primary");
        assertSuccess(api("POST", taskPath(claim.id()) + "/decisions", decision(claim, "APPROVE", "Verified by a human reviewer"), "p5.primary"));
        assertThat(text("SELECT review_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("APPROVED");
        assertThat(text("SELECT reward_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT budget_amount FROM campaign WHERE id=8501", java.math.BigDecimal.class)).isEqualByComparingTo("1000.00");
        assertThat(text("SELECT status FROM review_approval_intent WHERE report_id=?", item.reportId())).isEqualTo("AWAITING_REWARD_INTEGRATION");
        assertThat(jdbc.queryForObject("SELECT amount FROM review_approval_intent WHERE report_id=?", java.math.BigDecimal.class, item.reportId())).isEqualByComparingTo("3.50");
        assertThat(number("SELECT user_id FROM review_approval_intent WHERE report_id=?", item.reportId())).isEqualTo(8101L);
        assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='REVIEW_APPROVED'", item.reportId())).isEqualTo(1L);
    }

    @Test
    void approvalPortDatabaseFailureRollsBackDecisionAuditReportIntentAndOutboxTogether() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claim = claim(workId(item), 0, "p5.primary");
        Map<String, Object> request = decision(claim, "APPROVE", "Approval must be atomic with preparation");
        long auditBefore = number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claim.id());
        long eventBefore = number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId());
        jdbc.execute("ALTER TABLE review_approval_intent ADD CONSTRAINT p5_test_reject_approval CHECK (1=0)");
        try {
            assertThat(api("POST", taskPath(claim.id()) + "/decisions", request, "p5.primary").status()).isBetween(500, 599);
            assertThat(text("SELECT status FROM review_task WHERE id=?", claim.id())).isEqualTo("CLAIMED");
            assertThat(version(claim.id())).isEqualTo(claim.version());
            assertThat(text("SELECT review_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("WAITING_REVIEW");
            assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", claim.id())).isZero();
            assertThat(number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claim.id())).isEqualTo(auditBefore);
            assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId())).isEqualTo(eventBefore);
            assertThat(number("SELECT COUNT(*) FROM review_approval_intent")).isZero();
        } finally { jdbc.execute("ALTER TABLE review_approval_intent DROP CHECK p5_test_reject_approval"); }
        assertSuccess(api("POST", taskPath(claim.id()) + "/decisions", request, "p5.primary"));
        assertThat(number("SELECT COUNT(*) FROM review_approval_intent WHERE report_id=?", item.reportId())).isEqualTo(1L);
    }

    @Test
    void leaseExpiringInsideApprovalPreparationRejectsTheFinalCasAndRollsBackEverything() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claimed = claim(workId(item), 0, "p5.primary");
        Timestamp originalLease = jdbc.queryForObject("SELECT lease_until FROM review_task WHERE id=?", Timestamp.class, claimed.id());
        long auditBefore = number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claimed.id());
        long outboxBefore = number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId());
        AtomicInteger injected = new AtomicInteger();
        // Observe the real approval port: its real INSERT succeeds before the same HTTP thread
        // expires the lease inside that real transaction. No privileged trigger or fake result.
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ApprovalPreparationPort.ApprovedDecision approved = invocation.getArgument(0);
            assertThat(jdbc.update("UPDATE review_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    approved.taskId())).isEqualTo(1);
            injected.incrementAndGet();
            return result;
        }).when(approvalPreparation).prepare(any(ApprovalPreparationPort.ApprovedDecision.class));
        Reply expired = api("POST", taskPath(claimed.id()) + "/decisions",
                decision(claimed, "APPROVE", "Approval cannot outlive its claim lease"), "p5.primary");
        assertThat(expired.status()).isEqualTo(409);
        assertThat(injected.get()).isEqualTo(1);
        assertThat(text("SELECT status FROM review_task WHERE id=?", claimed.id())).isEqualTo("CLAIMED");
        assertThat(version(claimed.id())).isEqualTo(claimed.version());
        assertThat(jdbc.queryForObject("SELECT lease_until FROM review_task WHERE id=?", Timestamp.class, claimed.id())).isEqualTo(originalLease);
        assertThat(text("SELECT review_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("WAITING_REVIEW");
        assertThat(text("SELECT reward_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("NONE");
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", claimed.id())).isZero();
        assertThat(number("SELECT COUNT(*) FROM review_approval_intent WHERE report_id=?", item.reportId())).isZero();
        assertThat(number("SELECT COUNT(*) FROM review_audit WHERE task_id=?", claimed.id())).isEqualTo(auditBefore);
        assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", item.reportId())).isEqualTo(outboxBefore);
    }

    @Test
    void evidenceOwnerCanReadActualPngAndJpegBytesButAnotherUserCannot() throws Exception {
        Fixture item = completed(false);
        byte[] expected = evidenceBytes(item);
        String path = "/api/v1/evidence/" + item.evidenceId() + "/content";
        HttpResponse<byte[]> pngResponse = binary(path, "p5.owner");
        assertBinary(pngResponse, expected, "image/png");
        assertThat(binary(path, "p5.plain").statusCode()).isEqualTo(403);
        assertThat(binary(path, "p5.manager").statusCode()).isEqualTo(403);
        byte[] jpeg = jpeg();
        String jpegKey = storage.write(jpeg, "jpg");
        String jpegHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jpeg));
        jdbc.update("UPDATE evidence SET object_key=?,sha256=?,media_type='image/jpeg',size_bytes=? WHERE id=?", jpegKey, jpegHash, jpeg.length, item.evidenceId());
        jdbc.update("UPDATE traffic_report SET evidence_sha256=? WHERE id=?", jpegHash, item.reportId());
        assertBinary(binary(path, "p5.owner"), jpeg, "image/jpeg");
    }

    @Test
    void reviewEvidenceRequiresAnActiveClaimUnlessAnExplicitSameRegionManagerReadsIt() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String path = taskPath(workId(item)) + "/evidence";
        assertThat(binary(path, "p5.primary").statusCode()).isEqualTo(403);
        assertThat(binary(path, "p5.owner").statusCode()).isEqualTo(403);
        assertBinary(binary(path, "p5.manager"), evidenceBytes(item), "image/png");
        claim(workId(item), 0, "p5.primary");
        assertBinary(binary(path, "p5.primary"), evidenceBytes(item), "image/png");
        assertThat(binary(path, "p5.rechecker").statusCode()).isEqualTo(403);
    }

    @Test
    void expiredClaimsAndCrossRegionManagersCannotFetchReviewEvidence() throws Exception {
        Fixture item = completed(false);
        accept(item);
        String task = workId(item), path = taskPath(task) + "/evidence";
        claim(task, 0, "p5.primary");
        expire(task);
        assertThat(binary(path, "p5.primary").statusCode()).isEqualTo(403);
        assertThat(binary(path, "p5.outsider").statusCode()).isEqualTo(403);
        // Administrative overview privilege does not widen the image's regional disclosure scope.
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES(8307,'identity:user:manage')");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8203,8307)");
        assertThat(binary(path, "p5.outsider").statusCode()).isEqualTo(403);
    }

    @Test
    void evidenceStorageIsReadOutsideTransactionsAndCorruptedBytesAreNeverServed() throws Exception {
        Fixture item = completed(false);
        accept(item);
        byte[] expected = evidenceBytes(item);
        claim(workId(item), 0, "p5.primary");
        var observed = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        doAnswer(invocation -> {
            boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            observed.add(inTransaction);
            assertThat(inTransaction).isFalse();
            return invocation.callRealMethod();
        }).when(storage).read(anyString());
        assertBinary(binary("/api/v1/evidence/" + item.evidenceId() + "/content", "p5.owner"), expected, "image/png");
        assertBinary(binary(taskPath(workId(item)) + "/evidence", "p5.primary"), expected, "image/png");
        byte[] corrupt = Arrays.copyOf(expected, expected.length);
        corrupt[corrupt.length - 1] ^= 1;
        String corruptKey = storage.write(corrupt, "png");
        jdbc.update("UPDATE evidence SET object_key=? WHERE id=?", corruptKey, item.evidenceId());
        HttpResponse<byte[]> rejected = binary("/api/v1/evidence/" + item.evidenceId() + "/content", "p5.owner");
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(new String(rejected.body(), StandardCharsets.UTF_8)).doesNotContain(STORAGE.toString(), corruptKey, "stackTrace");
        assertThat(observed).hasSizeGreaterThanOrEqualTo(3).containsOnly(false);
    }

    @Test
    void reportOwnerCanAppealOneRejectionWithoutErasingTheOriginalDecision() throws Exception {
        Fixture item = rejectedByPrimary();
        String task = workId(item);
        long before = version(task);
        assertThat(api("POST", "/api/v1/reports/" + item.reportId() + "/appeals", Map.of("reason", "Independent evidence deserves another review"), "p5.plain").status()).isEqualTo(403);
        Reply appealed = api("POST", "/api/v1/reports/" + item.reportId() + "/appeals", Map.of("reason", "Please reconsider the rejection"), "p5.owner");
        assertSuccess(appealed);
        assertThat(appealed.json().get("status")).isEqualTo("OPEN");
        assertThat(text("SELECT stage FROM review_task WHERE id=?", task)).isEqualTo("RECHECK");
        assertThat(text("SELECT status FROM review_task WHERE id=?", task)).isEqualTo("READY");
        assertThat(version(task)).isGreaterThan(before);
        assertThat(text("SELECT review_status FROM traffic_report WHERE id=?", item.reportId())).isEqualTo("RISK_REVIEW");
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", task)).isEqualTo(1L);
        assertThat(number("SELECT COUNT(*) FROM review_appeal WHERE report_id=?", item.reportId())).isEqualTo(1L);
        assertThat(api("POST", "/api/v1/reports/" + item.reportId() + "/appeals", Map.of("reason", "Repeated appeal"), "p5.owner").status()).isEqualTo(409);
    }

    @Test
    void appellantCannotReuseThePreviousDecisionMakerAsTheIndependentRechecker() throws Exception {
        Fixture item = rejectedByPrimary();
        String task = workId(item);
        assertSuccess(api("POST", "/api/v1/reports/" + item.reportId() + "/appeals", Map.of("reason", "Independent review requested"), "p5.owner"));
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(8201,8305)");
        assertThat(api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", version(task)), "p5.primary").status()).isEqualTo(403);
        Claim rechecker = claim(task, version(task), "p5.rechecker");
        assertSuccess(api("POST", taskPath(task) + "/decisions", decision(rechecker, "REJECT", "Independent assessment confirms rejection"), "p5.rechecker"));
        assertThat(number("SELECT COUNT(*) FROM review_decision WHERE task_id=?", task)).isEqualTo(2L);
        assertThat(text("SELECT status FROM review_appeal WHERE report_id=?", item.reportId())).isEqualTo("RESOLVED");
        assertThat(api("POST", "/api/v1/reports/" + item.reportId() + "/appeals", Map.of("reason", "Another attempt"), "p5.owner").status()).isEqualTo(409);
    }

    @Test
    void appealRequiresARejectedUnpaidReportAndAValidReason() throws Exception {
        Fixture awaiting = completed(false);
        accept(awaiting);
        assertThat(api("POST", "/api/v1/reports/" + awaiting.reportId() + "/appeals", Map.of("reason", "Not rejected yet"), "p5.owner").status()).isEqualTo(409);
        Fixture rejected = rejectedByPrimary();
        assertThat(api("POST", "/api/v1/reports/" + rejected.reportId() + "/appeals", Map.of("reason", ""), "p5.owner").status()).isEqualTo(400);
        jdbc.update("UPDATE traffic_report SET reward_status='ISSUED' WHERE id=?", rejected.reportId());
        assertThat(api("POST", "/api/v1/reports/" + rejected.reportId() + "/appeals", Map.of("reason", "Settled cases require compensation handling"), "p5.owner").status()).isEqualTo(409);
        assertThat(number("SELECT COUNT(*) FROM review_appeal")).isZero();
    }

    private Fixture rejectedByPrimary() throws Exception {
        Fixture item = completed(false);
        accept(item);
        Claim claimed = claim(workId(item), 0, "p5.primary");
        assertSuccess(api("POST", taskPath(claimed.id()) + "/decisions", decision(claimed, "REJECT", "Evidence does not meet the published rules"), "p5.primary"));
        return item;
    }

    private Fixture completed(boolean risk) {
        try {
            byte[] image = png();
            String key = storage.write(image, "png");
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
            long evidence = insert("INSERT INTO evidence(owner_id,object_key,sha256,media_type,size_bytes) VALUES(8101,?,?,'image/png',?)", key, digest, image.length);
            long report = insert("""
                    INSERT INTO traffic_report(campaign_id,user_id,evidence_id,evidence_sha256,region_code,location,description,
                    capture_time,reward_amount,detection_status,review_status,reward_status,request_key,request_hash)
                    VALUES(8501,8101,?,?,'NORTH','North gate','P5 fixture',?,3.50,'SUCCEEDED',?,'NONE',?,?)
                    """, evidence, digest, Timestamp.from(Instant.now().minusSeconds(60)), risk ? "RISK_REVIEW" : "WAITING_REVIEW",
                    UUID.randomUUID().toString(), "b".repeat(64));
            String task = UUID.randomUUID().toString(), eventId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO detection_task(id,report_id,status,attempt_count,version,model_version,result_score,result_threshold,completed_at)"
                    + " VALUES(?,?,'SUCCEEDED',1,1,'review-model-v1',?,0.5,CURRENT_TIMESTAMP)", task, report, risk ? 0.9 : 0.1);
            String payload = JSONObjectUtils.toJSONString(Map.of("schemaVersion", 1, "eventId", eventId, "eventType", "DETECTION_COMPLETED",
                    "taskId", task, "reportId", report, "evidenceId", evidence, "userId", 8101));
            jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count)"
                    + " VALUES(?,'REPORT',?,'DETECTION_COMPLETED',?,'PENDING',0)", eventId, report, payload);
            ReviewEvent event = new ReviewEvent(1, eventId, "DETECTION_COMPLETED", task, report, evidence, 8101);
            return new Fixture(report, evidence, task, eventId, payload, event);
        } catch (Exception exception) { throw new IllegalStateException("Cannot create isolated review fixture", exception); }
    }

    private void accept(Fixture item) { assertThat(completionConsumer.process(item.event()).toString()).isEqualTo("ACK"); }
    private long workCount(long report) { return number("SELECT COUNT(*) FROM review_task WHERE report_id=?", report); }
    private String workId(Fixture item) { return text("SELECT id FROM review_task WHERE report_id=?", item.reportId()); }
    private long version(String task) { return number("SELECT version FROM review_task WHERE id=?", task); }
    private long auditCount(String task, String operation) { return number("SELECT COUNT(*) FROM review_audit WHERE task_id=? AND operation=?", task, operation); }
    private void expire(String task) { jdbc.update("UPDATE review_task SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(2)), task); }
    private static String taskPath(String task) { return "/api/v1/review-tasks/" + task; }

    private Claim claim(String task, long expected, String username) throws Exception {
        Reply response = api("POST", taskPath(task) + "/claim", Map.of("expectedVersion", expected), username);
        assertSuccess(response);
        return new Claim((String) response.json().get("id"), num(response.json().get("version")), (String) response.json().get("leaseToken"),
                (String) response.json().get("stage"), num(response.json().get("assigneeId")));
    }

    private static Map<String, Object> decision(Claim claim, String decision, String reason) {
        return Map.of("version", claim.version(), "leaseToken", claim.leaseToken(), "decision", decision, "reason", reason);
    }

    private long insert(String sql, Object... parameters) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement;
        }, holder);
        assertThat(holder.getKey()).isNotNull();
        return holder.getKey().longValue();
    }

    private void clearDatabase() {
        for (String table : List.of("review_appeal", "review_approval_intent", "review_decision", "review_audit", "review_task", "message_inbox",
                "detection_operation", "detection_attempt", "outbox_event", "detection_task", "traffic_report", "evidence", "campaign",
                "identity_user_region", "identity_user_role", "identity_role_permission", "identity_permission", "identity_role", "identity_user", "identity_region"))
            jdbc.update("DELETE FROM " + table);
    }

    private void removeOnlyOwnedFaultInjectionArtifacts() {
        // A forcibly terminated test JVM can miss its finally block. Recover only these exact,
        // suite-owned artifacts after verifying the dedicated test database above.
        for (var fault : List.of(Map.entry("review_task", "p5_test_reject_creation"),
                Map.entry("review_approval_intent", "p5_test_reject_approval"))) {
            long exists = number("SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? AND constraint_type='CHECK'",
                    fault.getKey(), fault.getValue());
            if (exists > 0) jdbc.execute("ALTER TABLE " + fault.getKey() + " DROP CHECK " + fault.getValue());
        }
    }

    private void startReviewListener() {
        var listener = listeners.getListenerContainer("trafficReviewCreationListener");
        assertThat(listener).isNotNull();
        listener.start();
    }

    private void stopListeners() {
        for (String id : List.of("trafficReviewCreationListener", "trafficDetectionListener")) {
            var listener = listeners.getListenerContainer(id);
            if (listener != null && listener.isRunning()) listener.stop();
        }
    }

    private void awaitBrokerReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                try (var connection = rawFactory().newConnection(); var channel = connection.createChannel()) { channel.confirmSelect(); channel.basicQos(1); }
                if (rabbit.getConnectionFactory() instanceof CachingConnectionFactory cache) cache.resetConnection();
                admin.initialize();
                Boolean ready = rabbit.execute(channel -> { channel.queueDeclarePassive(COMPLETED); channel.queueDeclarePassive(DEAD); channel.queueDeclarePassive(DECIDED); return true; });
                if (Boolean.TRUE.equals(ready)) return;
            } catch (Exception failure) { last = failure; }
            sleep(100);
        }
        throw new AssertionError("P5 private AMQP topology never became protocol-ready", last);
    }

    private void purgeOwnedQueues() {
        if (!NAMESPACE.startsWith("traffic-p5-it-")) throw new IllegalStateException("Not a private review test namespace");
        for (String queue : List.of(COMPLETED, DEAD, DECIDED, DETECTION)) admin.purgeQueue(queue, false);
    }

    private long queueDepth(String queue) {
        var properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    private void sendRaw(String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        String eventId = UUID.randomUUID().toString();
        try { if (parse(payload).get("eventId") instanceof String id) eventId = id; }
        catch (RuntimeException invalidJson) { /* Poison messages deliberately lack a valid event envelope. */ }
        properties.setMessageId(eventId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(EXCHANGE, "detection.completed", new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
    }

    private byte[] evidenceBytes(Fixture item) {
        return storage.read(text("SELECT object_key FROM evidence WHERE id=?", item.evidenceId()));
    }

    private HttpResponse<byte[]> binary(String path, String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + bearer(username)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void assertBinary(HttpResponse<byte[]> response, byte[] expected, String mediaType) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith(mediaType);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(response.headers().map().toString()).doesNotContain(STORAGE.toString(), "objectKey", "object_key");
    }

    private Reply api(String method, String path, Map<String, ?> body, String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer(username))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.body().startsWith("{") ? parse(response.body()) : Map.of());
    }

    private String bearer(String username) throws Exception {
        if (tokens.containsKey(username)) return tokens.get(username);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username", username, "password", PASSWORD)))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = (String) parse(response.body()).get("accessToken");
        sessions.add("traffic:auth:session:" + SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));
        tokens.put(username, token);
        return token;
    }

    private static com.rabbitmq.client.ConnectionFactory rawFactory() {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort(25672); factory.setUsername("traffic_app"); factory.setPassword("Traffic_mq_local_only_2026");
        factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(1000); factory.setHandshakeTimeout(3000);
        return factory;
    }

    private long number(String sql, Object... arguments) { return jdbc.queryForObject(sql, Long.class, arguments); }
    private String text(String sql, Object... arguments) { return jdbc.queryForObject(sql, String.class, arguments); }
    private static long num(Object value) { assertThat(value).isInstanceOf(Number.class); return ((Number) value).longValue(); }
    private static Map<String, Object> parse(String body) { try { return JSONObjectUtils.parse(body); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static Map<String, Object> changed(Map<String, ?> source, String name, Object value) { Map<String, Object> result = new HashMap<>(source); result.put(name, value); return result; }
    private static void assertSuccess(Reply reply) { assertThat(reply.status()).withFailMessage("Expected HTTP 200, received %s: %s", reply.status(), reply.body()).isEqualTo(200); }
    private static List<String> listIds(Reply reply) {
        assertSuccess(reply);
        assertThat(reply.json().get("items")).isInstanceOf(List.class);
        return ((List<?>) reply.json().get("items")).stream().map(item -> (String) ((Map<?, ?>) item).get("id")).toList();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try { if (condition.getAsBoolean()) return; } catch (RuntimeException failure) { last = failure; }
            sleep(70);
        }
        throw new AssertionError("P5 condition did not become true before its deadline", last);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static Path privateStorage() {
        try { Path target = Path.of("target").toAbsolutePath().normalize(); Files.createDirectories(target); return Files.createTempDirectory(target, "p5-evidence-").toRealPath(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void removeStorage(boolean root) throws Exception {
        Path checked = STORAGE.toAbsolutePath().normalize();
        if (!checked.startsWith(STORAGE.getParent().toAbsolutePath().normalize()) || !checked.getFileName().toString().startsWith("p5-evidence-"))
            throw new IllegalStateException("Refusing an unverified evidence directory deletion");
        if (!Files.exists(checked)) return;
        try (var paths = Files.walk(checked)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) if (root || !path.equals(checked)) Files.deleteIfExists(path);
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, UUID.randomUUID().hashCode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x1478A1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", bytes)).isTrue();
        return bytes.toByteArray();
    }

    private record Fixture(long reportId, long evidenceId, String detectionId, String eventId, String payload, ReviewEvent event) { }
    private record Claim(String id, long version, String leaseToken, String stage, long assignee) { }
    private record Reply(int status, String body, Map<String, Object> json) { }
}
