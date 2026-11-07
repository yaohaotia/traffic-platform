package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yao.traffic.detection.DetectionModels;
import com.yao.traffic.detection.DetectionProcessor;
import com.yao.traffic.detection.DetectionTasks;
import com.yao.traffic.detection.ModelClient;
import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.messaging.OutboxPublisher;
import com.yao.traffic.messaging.OutboxStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
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
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real broker and database. The HTTP stub replaces model weights, not message transport or transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableDetectionIntegrationTest {
    private static final String NAMESPACE = "traffic-p4-it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String QUEUE = NAMESPACE + ".detection", COMPLETED = NAMESPACE + ".completed",
            DEAD = NAMESPACE + ".dead", EXCHANGE = NAMESPACE + ".events";
    private static final Path STORAGE = privateStorage();
    private static final ModelStub MODEL = new ModelStub();
    private static final String PASSWORD = "P4-real-services-password-2026!";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired EvidenceStorage storage;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxStore outboxes;
    @Autowired DetectionTasks tasks;
    @Autowired DetectionProcessor processor;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoSpyBean ModelClient modelClient;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, String> tokens = new HashMap<>();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final List<Boolean> transactionObservations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String hash;

    @DynamicPropertySource
    static void isolatedBrokerAndHttpStub(DynamicPropertyRegistry properties) {
        properties.add("traffic.messaging.enabled", () -> true);
        properties.add("traffic.messaging.namespace", () -> NAMESPACE);
        properties.add("traffic.messaging.polling-enabled", () -> false);
        properties.add("traffic.messaging.listener-enabled", () -> false);
        properties.add("traffic.messaging.confirm-timeout-ms", () -> 1000);
        properties.add("traffic.messaging.outbox-lease-seconds", () -> 5);
        properties.add("traffic.messaging.max-publish-attempts", () -> 3);
        properties.add("traffic.detection.model-url", MODEL::url);
        properties.add("traffic.detection.http-timeout-ms", () -> 700);
        properties.add("traffic.detection.lease-seconds", () -> 5);
        properties.add("traffic.detection.max-attempts", () -> 2);
        properties.add("traffic.detection.retry-base-ms", () -> 20);
        properties.add("traffic.detection.pending-resignal-seconds", () -> 5);
        properties.add("traffic.storage.provider", () -> "local");
        properties.add("traffic.storage.local-root", STORAGE::toString);
        properties.add("spring.rabbitmq.host", () -> "127.0.0.1");
        properties.add("spring.rabbitmq.port", () -> 25672);
        properties.add("spring.rabbitmq.username", () -> "traffic_app");
        properties.add("spring.rabbitmq.password", () -> "Traffic_mq_local_only_2026");
        properties.add("spring.rabbitmq.connection-timeout", () -> "1s");
        properties.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        properties.add("spring.rabbitmq.publisher-returns", () -> true);
        properties.add("spring.rabbitmq.template.mandatory", () -> true);
        properties.add("spring.rabbitmq.listener.simple.prefetch", () -> 1);
        properties.add("spring.rabbitmq.listener.simple.concurrency", () -> 1);
        properties.add("spring.rabbitmq.listener.simple.max-concurrency", () -> 1);
        properties.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        properties.add("management.health.rabbit.enabled", () -> true);
    }

    @BeforeAll void initialize() { hash = passwords.encode(PASSWORD); }

    @BeforeEach
    void prepareIsolatedResources() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("traffic_platform_it");
        stopListener();
        awaitBrokerReady();
        purgeOwnedQueues();
        clearDatabase();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES"
                + "(7101,'p4.owner',?,TRUE,0),(7102,'p4.manager',?,TRUE,0),(7103,'p4.outsider',?,TRUE,0)", hash, hash, hash);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(7201,'P4_OWNER'),(7202,'P4_MANAGER')");
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES"
                + "(7301,'report:read'),(7302,'detection:manage'),(7303,'message:manage')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(7101,7201),(7102,7202),(7103,7202)");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES"
                + "(7201,7301),(7202,7301),(7202,7302),(7202,7303)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(7401,'NORTH','North campus'),(7402,'SOUTH','South campus')");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(7101,7401),(7102,7401),(7103,7402)");
        jdbc.update("INSERT INTO campaign(id,title,region_code,starts_at,ends_at,reward_amount,budget_amount,state,created_by)"
                + " VALUES(7501,'P4 detection campaign','NORTH',?,?,3.50,100.00,'PUBLISHED',7102)",
                Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().plusSeconds(3600)));
        MODEL.reset();
        tokens.clear();
        transactionObservations.clear();
        // This spy observes the real adapter and then performs its real multipart HTTP request.
        doAnswer(invocation -> {
            transactionObservations.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(modelClient).detect(any(DetectionModels.Claim.class));
    }

    @AfterEach
    void cleanOnlySuiteResources() throws Exception {
        MODEL.release.countDown();
        stopListener();
        clearDatabase();
        sessions.forEach(redis::delete);
        sessions.clear();
        awaitBrokerReady();
        purgeOwnedQueues();
        removeStorage(false);
    }

    @AfterAll
    void shutdownPrivateResources() throws Exception {
        MODEL.close();
        stopListener();
        for (String queue : List.of(QUEUE, COMPLETED, DEAD)) admin.deleteQueue(queue);
        admin.deleteExchange(EXCHANGE);
        admin.deleteExchange(NAMESPACE + ".dead.exchange");
        removeStorage(true);
    }

    @Test
    void realOutboxConfirmsConsumerAndModelPersistAReviewableResult() throws Exception {
        Fixture item = pending();
        startListener();
        assertThat(publisher.publishPending()).isGreaterThanOrEqualTo(1);
        await(() -> taskStatus(item).equals("SUCCEEDED"));
        assertThat(outboxStatus(item.eventId())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT review_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("WAITING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT reward_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT model_version FROM detection_task WHERE id=?", String.class, item.taskId())).isEqualTo("stub-model-v1");
        assertThat(jdbc.queryForObject("SELECT result_score FROM detection_task WHERE id=?", Double.class, item.taskId())).isEqualTo(0.15);
        assertThat(jdbc.queryForObject("SELECT result_threshold FROM detection_task WHERE id=?", Double.class, item.taskId())).isEqualTo(0.5);
        assertThat(MODEL.calls.get()).isEqualTo(1);
        assertThat(MODEL.lastContentType.get()).startsWith("multipart/form-data;");
        assertThat(MODEL.lastBody.get()).contains("name=\"image\"");
        assertThat(transactionObservations).containsExactly(false);
        assertThat(completedCount(item.reportId())).isEqualTo(1L);
        publisher.publishPending();
        await(() -> queueDepth(COMPLETED) == 1);
    }

    @Test
    void highRiskResultRequiresHumanReviewWithoutPunishingOrRewardingTheUser() throws Exception {
        MODEL.mode.set(Mode.HIGH);
        Fixture item = pending();
        startListener();
        publisher.publishPending();
        await(() -> taskStatus(item).equals("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT review_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("RISK_REVIEW");
        assertThat(jdbc.queryForObject("SELECT reward_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity_user WHERE id=7101", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT auth_version FROM identity_user WHERE id=7101", Long.class)).isZero();
    }

    @Test
    void duplicateBrokerSignalsPerformOneEffectiveInferenceAndEmitOneCompletion() throws Exception {
        Fixture item = pending();
        startListener();
        publisher.publishPending();
        for (int index = 0; index < 5; index++) sendRaw(item.payload());
        await(() -> taskStatus(item).equals("SUCCEEDED") && queueDepth(QUEUE) == 0);
        assertThat(MODEL.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM detection_task WHERE id=?", Integer.class, item.taskId())).isEqualTo(1);
        assertThat(completedCount(item.reportId())).isEqualTo(1L);
    }

    @Test
    void unroutableReturnCannotBeMistakenForSuccessfulPublisherConfirmation() throws Exception {
        Fixture item = pending();
        Binding binding = new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, "detection.requested", Map.of());
        admin.removeBinding(binding);
        try {
            assertThat(publisher.publishPending()).isZero();
            assertThat(outboxStatus(item.eventId())).isNotEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_event WHERE event_id=?", String.class, item.eventId())).isNotBlank();
            assertThat(queueDepth(QUEUE)).isZero();
        } finally { admin.declareBinding(binding); }
        makeOutboxDue(item.eventId());
        assertThat(publisher.publishPending()).isGreaterThanOrEqualTo(1);
        assertThat(outboxStatus(item.eventId())).isEqualTo("SENT");
        assertThat(queueDepth(QUEUE)).isEqualTo(1);
    }

    @Test
    void brokerStopAndRestartPreserveTheCommittedTaskAndAllowPublicationRecovery() throws Exception {
        Fixture item = pending();
        try {
            dockerCompose("stop", "rabbitmq");
            publisher.publishPending();
            assertThat(outboxStatus(item.eventId())).isNotEqualTo("SENT");
            assertThat(taskStatus(item)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM traffic_report WHERE id=?", Long.class, item.reportId())).isEqualTo(1L);
        } finally {
            dockerCompose("up", "-d", "--wait", "--wait-timeout", "90", "rabbitmq");
            awaitBrokerReady();
        }
        makeOutboxDue(item.eventId());
        await(() -> { publisher.publishPending(); return outboxStatus(item.eventId()).equals("SENT"); });
        startListener();
        await(() -> taskStatus(item).equals("SUCCEEDED"));
    }

    @Test
    void expiredOutboxLeaseRejectsLateSuccessFromAnOlderPublisherAttempt() {
        Fixture item = pending();
        OutboxStore.Delivery old = outboxes.claim(1).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until=? WHERE event_id=?", Timestamp.from(Instant.now().minusSeconds(2)), item.eventId());
        assertThat(outboxes.recoverExpired()).isGreaterThanOrEqualTo(1);
        makeOutboxDue(item.eventId());
        OutboxStore.Delivery current = outboxes.claim(1).getFirst();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(outboxes.markPublished(old.eventId(), old.leaseToken())).isFalse();
        assertThat(outboxStatus(item.eventId())).isEqualTo("PUBLISHING");
        assertThat(outboxes.markPublished(current.eventId(), current.leaseToken())).isTrue();
        assertThat(outboxStatus(item.eventId())).isEqualTo("SENT");
    }

    @Test
    void modelHttpRunsOutsideTransactionsAndDoesNotKeepTaskOrReportRowsLocked() throws Exception {
        Fixture item = pending();
        MODEL.mode.set(Mode.BLOCK);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(() -> processor.process(item.event()));
            assertThat(MODEL.entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT id FROM detection_task WHERE id='" + item.taskId() + "' FOR UPDATE NOWAIT").close();
                    statement.executeQuery("SELECT id FROM traffic_report WHERE id=" + item.reportId() + " FOR UPDATE NOWAIT").close();
                } finally { connection.rollback(); }
            }
            MODEL.release.countDown();
            execution.get(10, TimeUnit.SECONDS);
        } finally { MODEL.release.countDown(); }
        assertThat(taskStatus(item)).isEqualTo("SUCCEEDED");
        assertThat(transactionObservations).containsExactly(false);
    }

    @Test
    void expiredExecutionCannotOverwriteAResultFromItsReplacementLease() {
        Fixture item = pending();
        DetectionModels.Claim old = tasks.claim(item.event()).orElseThrow();
        jdbc.update("UPDATE detection_task SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(2)), item.taskId());
        tasks.recoverAndEnqueue();
        makeTaskDue(item.taskId());
        tasks.recoverAndEnqueue();
        DetectionModels.Claim current = tasks.claim(item.event()).orElseThrow();
        assertThat(current.version()).isGreaterThan(old.version());
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(tasks.succeed(old, new DetectionModels.Result(0.9, 0.5, "old-model", true))).isFalse();
        assertThat(tasks.fail(old, "LATE_ERROR", false)).isFalse();
        assertThat(tasks.succeed(current, new DetectionModels.Result(0.1, 0.5, "new-model", false))).isTrue();
        assertThat(jdbc.queryForObject("SELECT model_version FROM detection_task WHERE id=?", String.class, item.taskId())).isEqualTo("new-model");
        assertThat(jdbc.queryForObject("SELECT review_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("WAITING_REVIEW");
        assertThat(completedCount(item.reportId())).isEqualTo(1L);
    }

    @Test
    void committedResultThenChannelLossBeforeAckRedeliversWithoutAnotherInference() throws Exception {
        Fixture item = pending();
        publisher.publishPending();
        com.rabbitmq.client.ConnectionFactory factory = rawFactory();
        try (var connection = factory.newConnection()) {
            Channel channel = connection.createChannel();
            GetResponse first = channel.basicGet(QUEUE, false);
            assertThat(first).isNotNull();
            assertThat(first.getProps().getDeliveryMode()).isEqualTo(2);
            assertThat(first.getProps().getMessageId()).isEqualTo(item.eventId());
            processor.process(item.event());
            assertThat(taskStatus(item)).isEqualTo("SUCCEEDED");
            channel.close(); // No ACK: a real broker must redeliver this delivery.
            try (Channel replacement = connection.createChannel()) {
                AtomicReference<GetResponse> redelivery = new AtomicReference<>();
                await(() -> {
                    try { redelivery.set(replacement.basicGet(QUEUE, false)); return redelivery.get() != null; }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                });
                assertThat(redelivery.get().getEnvelope().isRedeliver()).isTrue();
                processor.process(item.event());
                replacement.basicAck(redelivery.get().getEnvelope().getDeliveryTag(), false);
            }
        }
        assertThat(MODEL.calls.get()).isEqualTo(1);
        assertThat(completedCount(item.reportId())).isEqualTo(1L);
        assertThat(queueDepth(QUEUE)).isZero();
    }

    @Test
    void unsupportedOrMalformedMessagesGoToTheOwnedDeadLetterQueue() throws Exception {
        Fixture item = pending();
        startListener();
        sendRaw("not-json");
        Map<String, Object> unsupported = new HashMap<>(JSONObjectUtils.parse(item.payload()));
        unsupported.put("schemaVersion", 99);
        sendRaw(JSONObjectUtils.toJSONString(unsupported));
        Map<String, Object> mismatched = new HashMap<>(JSONObjectUtils.parse(item.payload()));
        mismatched.put("evidenceId", item.evidenceId() + 9999);
        sendRaw(JSONObjectUtils.toJSONString(mismatched));
        await(() -> queueDepth(DEAD) == 3);
        for (int index = 0; index < 3; index++) {
            Message dead = rabbit.receive(DEAD, 2000);
            assertThat(dead).isNotNull();
            assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
        }
        assertThat(MODEL.calls.get()).isZero();
        assertThat(taskStatus(item)).isEqualTo("PENDING");
    }

    @Test
    void invalidModelResponsesNeverBecomeSuccessfulOrAutomaticPunishment() {
        for (Mode mode : List.of(Mode.INVALID_SCORE, Mode.INVALID_SCHEMA, Mode.CONFLICTING_BOOLEAN,
                Mode.NAN, Mode.INVALID_JSON, Mode.OVERSIZED)) {
            MODEL.mode.set(mode);
            Fixture item = pending();
            processor.process(item.event());
            assertThat(taskStatus(item)).as(mode.name()).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM detection_task WHERE id=?", String.class, item.taskId())).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT reward_status FROM traffic_report WHERE id=?", String.class, item.reportId())).isEqualTo("NONE");
            assertThat(completedCount(item.reportId())).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity_user WHERE id=7101", Boolean.class)).isTrue();
    }

    @Test
    void httpTimeoutPersistsRetryStateInsteadOfInventingAValidModelVerdict() throws Exception {
        MODEL.mode.set(Mode.BLOCK);
        Fixture item = pending();
        try { processor.process(item.event()); }
        finally { MODEL.release.countDown(); }
        assertThat(taskStatus(item)).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM detection_task WHERE id=?", Integer.class, item.taskId())).isEqualTo(1);
        assertThat(completedCount(item.reportId())).isZero();
    }

    @Test
    void finiteRetriesReachDeadWithoutStarvingOtherNewTasks() {
        Fixture failed = pending();
        MODEL.mode.set(Mode.UNAVAILABLE);
        processor.process(failed.event());
        assertThat(taskStatus(failed)).isEqualTo("RETRY_WAIT");
        Fixture healthy = pending();
        MODEL.mode.set(Mode.LOW);
        processor.process(healthy.event());
        assertThat(taskStatus(healthy)).isEqualTo("SUCCEEDED");
        makeTaskDue(failed.taskId());
        tasks.recoverAndEnqueue();
        MODEL.mode.set(Mode.UNAVAILABLE);
        processor.process(failed.event());
        assertThat(taskStatus(failed)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM detection_task WHERE id=?", Integer.class, failed.taskId())).isEqualTo(2);
        tasks.recoverAndEnqueue();
        assertThat(taskStatus(failed)).isEqualTo("DEAD");
        Fixture next = pending();
        MODEL.mode.set(Mode.LOW);
        processor.process(next.event());
        assertThat(taskStatus(next)).isEqualTo("SUCCEEDED");
    }

    @Test
    void pendingTaskWithLostWakeupIsReSignalledAndCanRecover() {
        Fixture item = pending();
        jdbc.update("UPDATE outbox_event SET status='SENT',published_at=CURRENT_TIMESTAMP WHERE event_id=?", item.eventId());
        jdbc.update("UPDATE detection_task SET last_signal_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), item.taskId());
        assertThat(tasks.recoverAndEnqueue()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='PENDING'", Long.class, item.reportId())).isGreaterThanOrEqualTo(1L);
        startListener();
        publisher.publishPending();
        await(() -> taskStatus(item).equals("SUCCEEDED"));
    }

    @Test
    void manualRetryRequiresPermissionRegionReasonAndAnEligibleTaskState() throws Exception {
        Fixture item = pending();
        DetectionModels.Claim claim = tasks.claim(item.event()).orElseThrow();
        assertThat(tasks.fail(claim, "INVALID_MODEL", false)).isTrue();
        String path = "/api/v1/detection-tasks/" + item.taskId();
        Reply progress = api("GET", path, null, "p4.owner");
        assertThat(progress.status()).isEqualTo(200);
        assertThat(progress.body()).doesNotContain(STORAGE.toString(), "object_key", "objectKey", "stackTrace");
        assertThat(api("POST", path + "/retry", Map.of("reason", "Try again"), "p4.owner").status()).isEqualTo(403);
        assertThat(api("POST", path + "/retry", Map.of("reason", "Try again"), "p4.outsider").status()).isEqualTo(403);
        assertThat(api("POST", path + "/retry", Map.of("reason", ""), "p4.manager").status()).isEqualTo(400);
        long before = eventCount(item.reportId());
        assertThat(api("POST", path + "/retry", Map.of("reason", "Model service has been repaired"), "p4.manager").status()).isEqualTo(200);
        assertThat(taskStatus(item)).isEqualTo("PENDING");
        assertThat(eventCount(item.reportId())).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM detection_operation WHERE task_id=?", Long.class, item.taskId())).isEqualTo(1L);
        assertThat(api("POST", path + "/retry", Map.of("reason", "Repeated click"), "p4.manager").status()).isEqualTo(409);
        assertThat(eventCount(item.reportId())).isEqualTo(before + 1);
    }

    @Test
    void aFinalReviewedReportCannotBeManuallyDetectedAgain() throws Exception {
        Fixture item = pending();
        assertThat(tasks.fail(tasks.claim(item.event()).orElseThrow(), "INVALID_MODEL", false)).isTrue();
        jdbc.update("UPDATE traffic_report SET review_status='APPROVED' WHERE id=?", item.reportId());
        assertThat(api("POST", "/api/v1/detection-tasks/" + item.taskId() + "/retry",
                Map.of("reason", "Must not overwrite a final review"), "p4.manager").status()).isEqualTo(409);
        assertThat(taskStatus(item)).isEqualTo("DEAD");
    }

    @Test
    void manualOutboxRetryPreservesEventIdentityAndRejectsCrossRegionOperations() throws Exception {
        Fixture item = pending();
        jdbc.update("UPDATE outbox_event SET status='DEAD',last_error_code='TEST_FAILURE' WHERE event_id=?", item.eventId());
        String path = "/api/v1/outbox-events/" + item.eventId() + "/retry";
        assertThat(api("POST", path, Map.of("reason", "Retry"), "p4.owner").status()).isEqualTo(403);
        assertThat(api("POST", path, Map.of("reason", "Retry"), "p4.outsider").status()).isEqualTo(403);
        assertThat(api("POST", path, Map.of("reason", ""), "p4.manager").status()).isEqualTo(400);
        assertThat(api("POST", path, Map.of("reason", "Broker routing repaired"), "p4.manager").status()).isEqualTo(200);
        assertThat(outboxStatus(item.eventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, item.eventId())).isEqualTo(item.payload());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id=?", Long.class, item.eventId())).isEqualTo(1L);
    }

    private Fixture pending() {
        try {
            byte[] bytes = png();
            String objectKey = storage.write(bytes, "png");
            String evidenceHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            long evidence = insert("INSERT INTO evidence(owner_id,object_key,sha256,media_type,size_bytes) VALUES(7101,?,?,'image/png',?)",
                    objectKey, evidenceHash, bytes.length);
            String requestKey = UUID.randomUUID().toString();
            long report = insert("""
                    INSERT INTO traffic_report(campaign_id,user_id,evidence_id,evidence_sha256,region_code,location,description,
                    capture_time,reward_amount,detection_status,review_status,reward_status,request_key,request_hash)
                    VALUES(7501,7101,?,?,'NORTH','North gate','P4 fixture',?,3.50,'PENDING','WAITING_DETECTION','NONE',?,?)
                    """, evidence, evidenceHash,
                    Timestamp.from(Instant.now().minusSeconds(60)), requestKey, "f".repeat(64));
            String task = UUID.randomUUID().toString(), event = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO detection_task(id,report_id,status,attempt_count,version) VALUES(?,?,'PENDING',0,0)", task, report);
            DetectionModels.Event signal = new DetectionModels.Event(1, event, "REPORT_SUBMITTED", task, report, evidence, 7101);
            String payload = JSONObjectUtils.toJSONString(Map.of("schemaVersion", 1, "eventId", event, "eventType", "REPORT_SUBMITTED",
                    "taskId", task, "reportId", report, "evidenceId", evidence, "userId", 7101));
            jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count)"
                    + " VALUES(?,'REPORT',?,'REPORT_SUBMITTED',?,'PENDING',0)", event, report, payload);
            return new Fixture(report, evidence, task, event, payload, signal);
        } catch (Exception exception) { throw new IllegalStateException("Cannot create the isolated P4 business fixture", exception); }
    }

    private long insert(String sql, Object... arguments) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, key);
        assertThat(key.getKey()).isNotNull();
        return key.getKey().longValue();
    }

    private void clearDatabase() {
        for (String table : List.of("detection_operation", "detection_attempt", "outbox_event", "detection_task", "traffic_report",
                "evidence", "campaign", "identity_user_region", "identity_user_role", "identity_role_permission", "identity_permission",
                "identity_role", "identity_user", "identity_region")) jdbc.update("DELETE FROM " + table);
    }

    private void startListener() {
        var listener = listeners.getListenerContainer("trafficDetectionListener");
        assertThat(listener).isNotNull();
        listener.start();
    }

    private void stopListener() {
        var listener = listeners.getListenerContainer("trafficDetectionListener");
        if (listener != null && listener.isRunning()) listener.stop();
    }

    private void purgeOwnedQueues() {
        if (!NAMESPACE.startsWith("traffic-p4-it-")) throw new IllegalStateException("Not a private P4 namespace");
        for (String queue : List.of(QUEUE, COMPLETED, DEAD)) admin.purgeQueue(queue, false);
    }

    /** Erlang ping/Compose health can precede the AMQP listener becoming usable after restart. */
    private void awaitBrokerReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                // A fresh authenticated connection plus a real channel RPC proves protocol readiness.
                // TCP connect alone would accept the socket while RabbitMQ is still booting.
                try (var connection = rawFactory().newConnection(); var channel = connection.createChannel()) {
                    channel.confirmSelect();
                    channel.basicQos(1);
                }
                if (rabbit.getConnectionFactory() instanceof CachingConnectionFactory cache) cache.resetConnection();
                admin.initialize();
                Boolean declared = rabbit.execute(channel -> {
                    channel.queueDeclarePassive(QUEUE);
                    channel.queueDeclarePassive(COMPLETED);
                    channel.queueDeclarePassive(DEAD);
                    return true;
                });
                if (Boolean.TRUE.equals(declared)) return;
            } catch (Exception failure) { last = failure; }
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the isolated AMQP broker", interrupted);
            }
        }
        throw new AssertionError("Private RabbitMQ never became authenticated AMQP/channel ready", last);
    }

    private void sendRaw(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(EXCHANGE, "detection.requested", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private long queueDepth(String queue) {
        var properties = admin.getQueueProperties(queue);
        return properties == null ? -1 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    private String taskStatus(Fixture item) { return jdbc.queryForObject("SELECT status FROM detection_task WHERE id=?", String.class, item.taskId()); }
    private String outboxStatus(String event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, event); }
    private long eventCount(long report) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Long.class, report); }
    private long completedCount(long report) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='DETECTION_COMPLETED'", Long.class, report); }
    private void makeOutboxDue(String event) { jdbc.update("UPDATE outbox_event SET next_attempt_at=? WHERE event_id=?", Timestamp.from(Instant.now().minusSeconds(2)), event); }
    private void makeTaskDue(String task) { jdbc.update("UPDATE detection_task SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(2)), task); }

    private Reply api(String method, String path, Map<String, ?> body, String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer(username))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private String bearer(String username) throws Exception {
        if (tokens.containsKey(username)) return tokens.get(username);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username", username, "password", PASSWORD)))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = (String) JSONObjectUtils.parse(response.body()).get("accessToken");
        sessions.add("traffic:auth:session:" + SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));
        tokens.put(username, token);
        return token;
    }

    private static com.rabbitmq.client.ConnectionFactory rawFactory() {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort(25672);
        factory.setUsername("traffic_app"); factory.setPassword("Traffic_mq_local_only_2026");
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(1000);
        factory.setHandshakeTimeout(3000);
        return factory;
    }

    private static void dockerCompose(String... arguments) throws Exception {
        Path compose = Path.of("dev", "compose.yml").toAbsolutePath().normalize();
        if (!Files.isRegularFile(compose) || !compose.endsWith(Path.of("dev", "compose.yml")))
            throw new IllegalStateException("Isolated Compose file was not found");
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", "traffic-platform-dev", "-f", compose.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(100, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("Docker command completed").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage("Isolated broker operation failed: %s", output).isZero();
    }

    private static void await(BooleanSupplier check) {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try { if (check.getAsBoolean()) return; }
            catch (RuntimeException failure) { last = failure; }
            try { Thread.sleep(80); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
        throw new AssertionError("P4 condition did not become true before its deadline", last);
    }

    private static Path privateStorage() {
        try { Path target = Path.of("target").toAbsolutePath().normalize(); Files.createDirectories(target); return Files.createTempDirectory(target, "p4-evidence-").toRealPath(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void removeStorage(boolean root) throws Exception {
        Path checked = STORAGE.toAbsolutePath().normalize();
        if (!checked.getFileName().toString().startsWith("p4-evidence-") || !checked.startsWith(STORAGE.getParent().toAbsolutePath().normalize()))
            throw new IllegalStateException("Not a private evidence directory");
        if (!Files.exists(checked)) return;
        try (var files = Files.walk(checked)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) if (root || !file.equals(checked)) Files.deleteIfExists(file);
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, UUID.randomUUID().hashCode());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private enum Mode { LOW, HIGH, BLOCK, UNAVAILABLE, INVALID_SCORE, INVALID_SCHEMA, CONFLICTING_BOOLEAN, NAN, INVALID_JSON, OVERSIZED }

    private static final class ModelStub implements AutoCloseable {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.LOW);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastContentType = new AtomicReference<>(), lastBody = new AtomicReference<>();
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ModelStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/v1/detect", this::handle);
                server.setExecutor(executor);
                server.start();
            } catch (Exception exception) { throw new IllegalStateException("Cannot start the private model protocol stub", exception); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/detect"; }
        void reset() { release.countDown(); calls.set(0); mode.set(Mode.LOW); entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        void handle(HttpExchange exchange) {
            try (exchange) {
                calls.incrementAndGet();
                lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                Mode current = mode.get();
                if (current == Mode.BLOCK) { entered.countDown(); release.await(15, TimeUnit.SECONDS); }
                int status = current == Mode.UNAVAILABLE ? 503 : 200;
                String response = switch (current) {
                    case HIGH -> "{\"schemaVersion\":1,\"score\":0.9,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":true}";
                    case INVALID_SCORE -> "{\"schemaVersion\":1,\"score\":1.2,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":true}";
                    case INVALID_SCHEMA -> "{\"schemaVersion\":2,\"score\":0.1,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":false}";
                    case CONFLICTING_BOOLEAN -> "{\"schemaVersion\":1,\"score\":0.9,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":false}";
                    case NAN -> "{\"schemaVersion\":1,\"score\":NaN,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":false}";
                    case INVALID_JSON -> "not-json";
                    case OVERSIZED -> "x".repeat(70_000);
                    case UNAVAILABLE -> "{\"error\":\"temporarily unavailable\"}";
                    default -> "{\"schemaVersion\":1,\"score\":0.15,\"threshold\":0.5,\"modelVersion\":\"stub-model-v1\",\"isAiGenerated\":false}";
                };
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // A client-side hard timeout intentionally closes the socket while BLOCK is active.
            }
        }
        @Override public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    private record Fixture(long reportId, long evidenceId, String taskId, String eventId, String payload, DetectionModels.Event event) { }
    private record Reply(int status, String body) { }
}
