package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.yao.traffic.messaging.OutboxPublisher;
import com.yao.traffic.reward.RewardEvents;
import com.yao.traffic.reward.LedgerReconciliation;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** P6: actual HTTP, MySQL row locks/rollback, Redis identities and RabbitMQ deliveries. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RewardIntegrationTest {
    static final String NS="traffic-p6-it-"+UUID.randomUUID().toString().substring(0,8);
    static final String PASSWORD="P6-ledger-integration-2026!";
    static final List<String> QUEUES=List.of(".detection",".completed",".dead",".review.decided",".reward",".notifications");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired OutboxPublisher publisher;
    @MockitoSpyBean RewardEvents events;
    @Autowired LedgerReconciliation reconciliation;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String,String> tokens=new ConcurrentHashMap<>();
    final Set<String> sessions=ConcurrentHashMap.newKeySet();
    String hash;

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p){
        p.add("traffic.messaging.enabled",()->true);p.add("traffic.messaging.namespace",()->NS);
        p.add("traffic.messaging.polling-enabled",()->false);p.add("traffic.messaging.listener-enabled",()->false);
        p.add("traffic.review.listener-enabled",()->false);p.add("traffic.review.polling-enabled",()->false);
        p.add("traffic.reward.enabled",()->true);p.add("traffic.reward.listener-enabled",()->false);p.add("traffic.reward.polling-enabled",()->false);
        p.add("spring.rabbitmq.listener.simple.concurrency",()->1);p.add("spring.rabbitmq.listener.simple.max-concurrency",()->1);
        p.add("spring.rabbitmq.listener.simple.auto-startup",()->false);
    }
    @BeforeAll void password(){hash=passwords.encode(PASSWORD);}
    @BeforeEach void prepare(){
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("traffic_platform_it");
        stop();admin.initialize();purge();removeFaults();clear();tokens.clear();
        for(var user:Map.of(9101L,"p6.owner",9102L,"p6.reviewer",9103L,"p6.manager",9104L,"p6.south",9105L,"p6.other").entrySet())
            jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(?,?,?,TRUE,0)",user.getKey(),user.getValue(),hash);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(9201,'P6_OWNER'),(9202,'P6_REVIEWER'),(9203,'P6_MANAGER')");
        var codes=List.of("wallet:read","wallet:withdraw","notification:read","review:claim","review:decide","report:review","reward:manage","withdrawal:manage");
        for(int i=0;i<codes.size();i++)jdbc.update("INSERT INTO identity_permission(id,code) VALUES(?,?)",9301+i,codes.get(i));
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(9101,9201),(9105,9201),(9102,9202),(9103,9203),(9104,9203)");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(9201,9301),(9201,9302),(9201,9303),"
                +"(9202,9304),(9202,9305),(9202,9306),(9203,9301),(9203,9303),(9203,9307),(9203,9308)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(9401,'NORTH','North'),(9402,'SOUTH','South')");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(9101,9401),(9102,9401),(9103,9401),(9104,9402),(9105,9401)");
        jdbc.update("INSERT INTO campaign(id,title,region_code,starts_at,ends_at,reward_amount,budget_amount,state,created_by) VALUES(9501,'P6 campaign','NORTH',?,?,3.50,100.00,'PUBLISHED',9103)",
                Timestamp.from(Instant.now().minusSeconds(3600)),Timestamp.from(Instant.now().plusSeconds(3600)));
    }
    @AfterEach void cleanup(){stop();removeFaults();clear();sessions.forEach(redis::delete);sessions.clear();purge();}
    @AfterAll void deleteNamespace(){stop();QUEUES.forEach(q->admin.deleteQueue(NS+q));admin.deleteExchange(NS+".events");admin.deleteExchange(NS+".dead.exchange");}

    @Test void approvalReservesSnapshotBudgetOrderAndOutboxInTheSameReviewTransaction()throws Exception{
        Work work=approved(9101);String order=order(work);
        assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
        assertThat(text("SELECT state FROM reward_order WHERE id=?",order)).isEqualTo("RESERVED");
        assertThat(text("SELECT reward_status FROM traffic_report WHERE id=?",work.report())).isEqualTo("RESERVED");
        assertThat(text("SELECT status FROM review_approval_intent WHERE report_id=?",work.report())).isEqualTo("INTEGRATED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",work.report())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();
    }
    @Test void concurrentApprovalsCannotExceedTheLastAvailableBudget()throws Exception{
        jdbc.update("UPDATE campaign SET budget_amount=3.50 WHERE id=9501");
        Work first=claimed(9101),second=claimed(9105);bearer("p6.reviewer");
        var replies=parallel(2,i->approve(i==0?first:second));
        assertThat(replies.stream().map(Reply::status).sorted().toList()).containsExactly(200,409);
        assertThat(count("SELECT COUNT(*) FROM reward_order")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM review_decision")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM review_task WHERE status='CLAIMED'")).isEqualTo(1);
        assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
    }
    @Test void insufficientBudgetRollsBackDecisionAndCanBeRetriedAfterAnAuditedIdempotentTopUp()throws Exception{
        jdbc.update("UPDATE campaign SET budget_amount=3.50 WHERE id=9501");approved(9105);Work pending=claimed(9101);
        assertThat(approve(pending).status()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM review_decision WHERE task_id=?",pending.task())).isZero();
        var addition=Map.of("amount",new BigDecimal("3.50"),"reason","Additional approved campaign allocation");
        ok(api("POST","/campaigns/9501/budget-additions",addition,"p6.manager","top-up-1"));
        ok(api("POST","/campaigns/9501/budget-additions",addition,"p6.manager","top-up-1"));
        assertThat(api("POST","/campaigns/9501/budget-additions",Map.of("amount",4,"reason",addition.get("reason")),"p6.manager","top-up-1").status()).isEqualTo(409);
        ok(approve(pending));
        assertThat(decimal("SELECT budget_amount FROM campaign WHERE id=9501")).isEqualByComparingTo("7.00");
        assertThat(count("SELECT COUNT(*) FROM budget_entry WHERE kind='TOP_UP'")).isEqualTo(1);
    }
    @Test void databaseOrderFailureRollsBackReviewIntentBudgetAndBothOutboxEvents()throws Exception{
        Work work=claimed(9101);jdbc.execute("ALTER TABLE reward_order ADD CONSTRAINT p6_test_order_failure CHECK(amount<3)");
        try{assertThat(approve(work).status()).isEqualTo(500);}
        finally{jdbc.execute("ALTER TABLE reward_order DROP CHECK p6_test_order_failure");}
        assertThat(text("SELECT status FROM review_task WHERE id=?",work.task())).isEqualTo("CLAIMED");
        assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isZero();
        for(String table:List.of("reward_order","review_decision","review_approval_intent","outbox_event","budget_entry"))assertThat(count("SELECT COUNT(*) FROM "+table)).isZero();
    }
    @Test void realRabbitDuplicateDeliveriesCreditOnceAndIndependentNotificationQueueAlsoReceivesEvents()throws Exception{
        Work work=approved(9101);var signal=signal(work);start("trafficRewardListener");start("trafficNotificationListener");
        publisher.publishPending();for(int i=0;i<8;i++)send(signal);
        await(()->count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")==1);
        publisher.publishPending();await(()->count("SELECT COUNT(*) FROM notification")==3);
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("3.50");
        assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isZero();
        assertThat(decimal("SELECT budget_spent FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
        assertThat(count("SELECT COUNT(*) FROM message_inbox WHERE consumer_name='reward-credit-v1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM message_inbox WHERE consumer_name='notification-v1'")).isEqualTo(3);
    }
    @Test void committedButUnackedDeliveryRedeliversAndDoesNotCreditAgain()throws Exception{
        Work work=approved(9101);var signal=signal(work);publisher.publishPending();
        try(var connection=factory().newConnection();var channel=connection.createChannel()){
            var first=channel.basicGet(NS+".reward",false);assertThat(first).isNotNull();
            assertThat(events.process(signal,false)).isTrue(); // commit, deliberately close without ACK
        }
        await(()->depth(".reward")==1);
        try(var connection=factory().newConnection();var channel=connection.createChannel()){
            var redelivery=channel.basicGet(NS+".reward",false);assertThat(redelivery).isNotNull();assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
            assertThat(events.process(signal,false)).isTrue();channel.basicAck(redelivery.getEnvelope().getDeliveryTag(),false);
        }
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")).isEqualTo(1);
    }
    @Test void databaseFailureRetainsMqDeliveryAndRecoversWithoutHalfWrittenBalances()throws Exception{
        Work work=approved(9101);jdbc.execute("ALTER TABLE wallet_account ADD CONSTRAINT p6_test_wallet_failure CHECK(available<3)");
        var failures=new AtomicInteger();
        doAnswer(invocation->{try{return invocation.callRealMethod();}catch(RuntimeException failure){failures.incrementAndGet();throw failure;}})
                .when(events).process(any(RewardEvents.Signal.class),eq(false));
        try{
            start("trafficRewardListener");publisher.publishPending();
            await(()->failures.get()>0);
            assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();
            assertThat(count("SELECT COUNT(*) FROM message_inbox WHERE consumer_name='reward-credit-v1'")).isZero();
            assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
        }finally{jdbc.execute("ALTER TABLE wallet_account DROP CHECK p6_test_wallet_failure");}
        await(()->count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")==1);
        assertThat(text("SELECT state FROM reward_order WHERE report_id=?",work.report())).isEqualTo("CREDITED");
    }
    @Test void fabricatedAndMalformedSignalsAreConfirmedIntoDeadQueueWithoutCrediting()throws Exception{
        start("trafficRewardListener");
        send(new RewardEvents.Signal(UUID.randomUUID().toString(),"REWARD_RESERVED",123456,UUID.randomUUID().toString()));
        rabbit.send(NS+".events","reward.reserved",new Message("{bad".getBytes(StandardCharsets.UTF_8),new MessageProperties()));
        await(()->depth(".dead")==2);assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();
    }
    @Test void cancellationReleasesReservationAndLateSignalCannotResurrectOrder()throws Exception{
        Work work=approved(9101);var signal=signal(work);String id=order(work);
        ok(api("POST","/rewards/"+id+"/corrections",Map.of("reason","Withdraw invalid duplicate award"),"p6.manager"));
        assertThat(events.process(signal,false)).isTrue();
        assertThat(text("SELECT state FROM reward_order WHERE id=?",id)).isEqualTo("CANCELLED");
        assertThat(decimal("SELECT budget_reserved FROM campaign WHERE id=9501")).isZero();
        assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();
    }
    @Test void repeatedCompensationReversesOnceAndPreservesOriginalCredit()throws Exception{
        Work work=credited(9101);String id=order(work);var reason=Map.of("reason","Award corrected after administrative investigation");
        ok(api("POST","/rewards/"+id+"/corrections",reason,"p6.manager"));ok(api("POST","/rewards/"+id+"/corrections",reason,"p6.manager"));
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='REVERSAL'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reward_correction")).isEqualTo(1);
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isZero();
        assertThat(decimal("SELECT budget_spent FROM campaign WHERE id=9501")).isZero();assertReconciled();
    }
    @Test void concurrentCreditAndCancellationHaveOneConsistentOutcome()throws Exception{
        Work work=approved(9101);String id=order(work);var signal=signal(work);bearer("p6.manager");
        var results=parallel(2,i->{if(i==0){assertThat(events.process(signal,false)).isTrue();return new Reply(200,"",Map.of());}
            return api("POST","/rewards/"+id+"/corrections",Map.of("reason","Concurrent correction"),"p6.manager");});
        results.forEach(RewardIntegrationTest::ok);
        assertThat(text("SELECT state FROM reward_correction WHERE order_id=?",id)).isEqualTo("APPLIED");
        assertThat(text("SELECT state FROM reward_order WHERE id=?",id)).isIn("CANCELLED","REVERSED");assertReconciled();
    }
    @Test void concurrentDifferentOrdersCreditTheSameWalletWithoutLostUpdates()throws Exception{
        var signals=new ArrayList<RewardEvents.Signal>();for(int i=0;i<6;i++)signals.add(signal(approved(9101)));
        parallel(6,i->{assertThat(events.process(signals.get(i),false)).isTrue();return true;});
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("21.00");
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")).isEqualTo(6);assertReconciled();
    }
    @Test void concurrentWithdrawalReplaysFreezeOnlyOnce()throws Exception{
        credited(9101);bearer("p6.owner");
        var replies=parallel(8,i->withdraw("withdraw-once",new BigDecimal("3.50")));replies.forEach(RewardIntegrationTest::ok);
        assertThat(replies.stream().map(r->r.json().get("id")).distinct().count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM withdrawal_order")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='FREEZE'")).isEqualTo(1);
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isZero();
        assertThat(decimal("SELECT frozen FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("3.50");assertReconciled();
    }
    @Test void withdrawalRequestsCannotReuseKeyForDifferentAmountOrOverdraw()throws Exception{
        credited(9101);ok(withdraw("same",new BigDecimal("2.00")));
        assertThat(withdraw("same",BigDecimal.ONE).status()).isEqualTo(409);
        assertThat(withdraw("new",new BigDecimal("2.00")).status()).isEqualTo(409);
        assertThat(withdraw("precision",new BigDecimal("0.001")).status()).isEqualTo(400);
        assertThat(withdraw("negative",new BigDecimal("-1.00")).status()).isEqualTo(400);assertReconciled();
    }
    @Test void applicantCannotApproveOwnWithdrawalEvenWithManagementPermission()throws Exception{
        credited(9101);String id=(String)withdraw("self",BigDecimal.ONE).json().get("id");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(9201,9308)");
        assertThat(api("POST","/withdrawals/"+id+"/decisions",Map.of("decision","APPROVE","reason","Self approval"),"p6.owner").status()).isEqualTo(403);
        assertThat(text("SELECT state FROM withdrawal_order WHERE id=?",id)).isEqualTo("REQUESTED");
    }
    @Test void repeatedSimulatedApprovalSettlesFrozenBalanceOnceAndRejectsConflictingDecision()throws Exception{
        credited(9101);String id=(String)withdraw("paid",new BigDecimal("2.00")).json().get("id");var decision=Map.of("decision","APPROVE","reason","Simulated settlement verified");
        ok(api("POST","/withdrawals/"+id+"/decisions",decision,"p6.manager"));ok(api("POST","/withdrawals/"+id+"/decisions",decision,"p6.manager"));
        assertThat(api("POST","/withdrawals/"+id+"/decisions",Map.of("decision","REJECT","reason","Conflicting result"),"p6.manager").status()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='SIMULATED_PAYOUT'")).isEqualTo(1);
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("1.50");assertReconciled();
    }
    @Test void frozenRewardCorrectionRequiresManualRecoveryAndDoesNotInventRefund()throws Exception{
        Work work=credited(9101);String withdrawal=(String)withdraw("frozen",new BigDecimal("3.50")).json().get("id");
        var reason=Map.of("reason","Reward eligibility corrected");String path="/rewards/"+order(work)+"/corrections";
        Reply correction=api("POST",path,reason,"p6.manager");ok(correction);assertThat(correction.json().get("state")).isEqualTo("MANUAL_REQUIRED");
        assertThat(decimal("SELECT budget_spent FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='REVERSAL'")).isZero();
        assertThat(withdraw("blocked",BigDecimal.ONE).status()).isEqualTo(409);
        assertThat(api("POST","/withdrawals/"+withdrawal+"/decisions",Map.of("decision","APPROVE","reason","Cannot bypass recovery"),"p6.manager").status()).isEqualTo(409);
        ok(api("POST","/withdrawals/"+withdrawal+"/decisions",Map.of("decision","REJECT","reason","Release balance for correction"),"p6.manager"));
        Reply retry=api("POST",path,reason,"p6.manager");ok(retry);assertThat(retry.json().get("state")).isEqualTo("APPLIED");assertReconciled();
    }
    @Test void oldApprovalIntentsRequireExplicitAuditedActivationAndCannotBePaidByRecoveryAlone()throws Exception{
        Work work=claimed(9101);String decision=UUID.randomUUID().toString(),intent=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO review_decision(id,task_id,task_version,stage,actor_id,decision,reason,lease_token_hash) VALUES(?,?,1,'PRIMARY',9102,'APPROVE','Historical P5 approval',?)",decision,work.task(),"a".repeat(64));
        jdbc.update("UPDATE review_task SET status='APPROVED',version=2 WHERE id=?",work.task());jdbc.update("UPDATE traffic_report SET review_status='APPROVED' WHERE id=?",work.report());
        jdbc.update("INSERT INTO review_approval_intent(id,decision_id,report_id,task_id,task_version,user_id,campaign_id,amount,status) VALUES(?,?,?,?,1,9101,9501,3.50,'AWAITING_REWARD_INTEGRATION')",intent,decision,work.report(),work.task());
        assertThat(events.recoverCredits()).isZero();assertThat(count("SELECT COUNT(*) FROM reward_order")).isZero();
        var input=Map.of("reason","Explicit reviewed migration activation");
        ok(api("POST","/approval-intents/"+intent+"/activate",input,"p6.manager"));ok(api("POST","/approval-intents/"+intent+"/activate",input,"p6.manager"));
        assertThat(count("SELECT COUNT(*) FROM reward_activation")).isEqualTo(1);assertThat(count("SELECT COUNT(*) FROM reward_order")).isEqualTo(1);
        assertThat(events.process(signal(work),false)).isTrue();assertReconciled();
    }
    @Test void moneyManagementAndOwnerQueriesEnforceRoleAndRegion()throws Exception{
        Work work=credited(9101);String id=order(work);
        assertThat(api("POST","/rewards/"+id+"/corrections",Map.of("reason","Other region"),"p6.south").status()).isEqualTo(403);
        assertThat(api("GET","/campaigns/9501/budget",null,"p6.south").status()).isEqualTo(403);
        assertThat(api("POST","/rewards/"+id+"/corrections",Map.of("reason","Owner manipulation"),"p6.owner").status()).isEqualTo(403);
        Reply own=api("GET","/wallet",null,"p6.owner"),other=api("GET","/wallet?userId=9101",null,"p6.other");ok(own);ok(other);
        assertThat(((Number)other.json().get("userId")).longValue()).isEqualTo(9105);assertThat(new BigDecimal(other.json().get("available").toString())).isZero();
        assertThat(api("GET","/rewards?page=0",null,"p6.owner").status()).isEqualTo(400);
    }
    @Test void notificationsDeduplicateAndReadStateBelongsOnlyToRecipient()throws Exception{
        Work work=credited(9101);var signal=signal(work);assertThat(events.process(signal,true)).isTrue();assertThat(events.process(signal,true)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);String id=text("SELECT id FROM notification");
        assertThat(api("POST","/notifications/"+id+"/read",Map.of(),"p6.other").status()).isEqualTo(404);
        ok(api("POST","/notifications/"+id+"/read",Map.of(),"p6.owner"));ok(api("POST","/notifications/"+id+"/read",Map.of(),"p6.owner"));
        assertThat(count("SELECT COUNT(*) FROM notification WHERE read_at IS NOT NULL")).isEqualTo(1);
        Reply list=api("GET","/notifications",null,"p6.other");ok(list);assertThat((List<?>)list.json().get("items")).isEmpty();
    }
    @Test void sentSignalRecoveryIsBoundedIdempotentAndDoesNotBypassDeadOutbox()throws Exception{
        Work work=approved(9101);jdbc.update("UPDATE outbox_event SET status='DEAD' WHERE event_type='REWARD_RESERVED'");
        assertThat(events.recoverCredits()).isZero();assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();
        jdbc.update("UPDATE outbox_event SET status='SENT' WHERE event_type='REWARD_RESERVED'");
        assertThat(events.recoverCredits()).isEqualTo(1);assertThat(events.recoverCredits()).isZero();
        assertThat(events.recoverNotifications()).isEqualTo(1);assertThat(events.recoverNotifications()).isZero();assertReconciled();
    }
    @Test void reconciliationDetectsTamperedBalancesWithoutSilentlyRepairingThem()throws Exception{
        credited(9101);assertReconciled();jdbc.update("UPDATE wallet_account SET available=available+1 WHERE user_id=9101");
        Reply check=api("GET","/wallet/reconciliation",null,"p6.owner");ok(check);assertThat(check.json().get("matches")).isEqualTo(false);
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("4.50");
        assertThat(reconciliation.sweep(0,0).checks()).anyMatch(c->!c.matches());
    }
    @Test void compensationEntryFailureRollsBackBalanceBudgetOrderAndCorrection()throws Exception{
        Work work=credited(9101);String id=order(work);var input=Map.of("reason","Reversal rollback check");
        jdbc.execute("ALTER TABLE wallet_entry ADD CONSTRAINT p6_test_entry_failure CHECK(kind<>'REVERSAL')");
        try{assertThat(api("POST","/rewards/"+id+"/corrections",input,"p6.manager").status()).isEqualTo(500);}
        finally{jdbc.execute("ALTER TABLE wallet_entry DROP CHECK p6_test_entry_failure");}
        assertThat(decimal("SELECT available FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("3.50");
        assertThat(decimal("SELECT budget_spent FROM campaign WHERE id=9501")).isEqualByComparingTo("3.50");
        assertThat(text("SELECT state FROM reward_order WHERE id=?",id)).isEqualTo("CREDITED");
        assertThat(count("SELECT COUNT(*) FROM reward_correction")).isZero();
        ok(api("POST","/rewards/"+id+"/corrections",input,"p6.manager"));assertReconciled();
    }
    @Test void simulatedPayoutFailureKeepsWithdrawalRequestedAndBalanceFrozen()throws Exception{
        credited(9101);String id=(String)withdraw("payout-rollback",new BigDecimal("3.50")).json().get("id");
        var input=Map.of("decision","APPROVE","reason","Payout rollback check");
        jdbc.execute("ALTER TABLE wallet_entry ADD CONSTRAINT p6_test_entry_failure CHECK(kind<>'SIMULATED_PAYOUT')");
        try{assertThat(api("POST","/withdrawals/"+id+"/decisions",input,"p6.manager").status()).isEqualTo(500);}
        finally{jdbc.execute("ALTER TABLE wallet_entry DROP CHECK p6_test_entry_failure");}
        assertThat(text("SELECT state FROM withdrawal_order WHERE id=?",id)).isEqualTo("REQUESTED");
        assertThat(decimal("SELECT frozen FROM wallet_account WHERE user_id=9101")).isEqualByComparingTo("3.50");
        ok(api("POST","/withdrawals/"+id+"/decisions",input,"p6.manager"));assertReconciled();
    }
    @Test void concurrentHistoryActivationReturnsTheSameOrderAndOneAudit()throws Exception{
        Work work=claimed(9101);String decision=UUID.randomUUID().toString(),intent=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO review_decision(id,task_id,task_version,stage,actor_id,decision,reason,lease_token_hash) VALUES(?,?,1,'PRIMARY',9102,'APPROVE','Historical approval',?)",decision,work.task(),"a".repeat(64));
        jdbc.update("UPDATE review_task SET status='APPROVED',version=2 WHERE id=?",work.task());jdbc.update("UPDATE traffic_report SET review_status='APPROVED' WHERE id=?",work.report());
        jdbc.update("INSERT INTO review_approval_intent(id,decision_id,report_id,task_id,task_version,user_id,campaign_id,amount,status) VALUES(?,?,?,?,1,9101,9501,3.50,'AWAITING_REWARD_INTEGRATION')",intent,decision,work.report(),work.task());
        bearer("p6.manager");var replies=parallel(4,i->api("POST","/approval-intents/"+intent+"/activate",Map.of("reason","Concurrent explicit activation"),"p6.manager"));
        replies.forEach(RewardIntegrationTest::ok);assertThat(replies.stream().map(r->r.json().get("id")).distinct().count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reward_activation")).isEqualTo(1);assertReconciled();
    }

    Work claimed(long owner)throws Exception{
        String digest=UUID.randomUUID().toString().replace("-","")+"a".repeat(32);
        long evidence=insert("INSERT INTO evidence(owner_id,object_key,sha256,media_type,size_bytes) VALUES(?,?,?,'image/png',10)",owner,UUID.randomUUID()+".png",digest);
        long report=insert("INSERT INTO traffic_report(campaign_id,user_id,evidence_id,evidence_sha256,region_code,location,description,capture_time,reward_amount,detection_status,review_status,reward_status,request_key,request_hash) VALUES(9501,?,?,?,'NORTH','Gate','P6 fixture',?,3.50,'SUCCEEDED','WAITING_REVIEW','NONE',?,?)",owner,evidence,digest,Timestamp.from(Instant.now()),UUID.randomUUID().toString(),"b".repeat(64));
        String task=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO review_task(id,report_id,source_event_id,source_detection_version,region_code,stage,status) VALUES(?,?,?,1,'NORTH','PRIMARY','READY')",task,report,UUID.randomUUID().toString());
        Reply claim=api("POST","/review-tasks/"+task+"/claim",Map.of("expectedVersion",0),"p6.reviewer");ok(claim);
        return new Work(report,task,((Number)claim.json().get("version")).longValue(),(String)claim.json().get("leaseToken"));
    }
    Work approved(long user)throws Exception{Work work=claimed(user);ok(approve(work));return work;}
    Work credited(long user)throws Exception{Work work=approved(user);assertThat(events.process(signal(work),false)).isTrue();return work;}
    Reply approve(Work work)throws Exception{return api("POST","/review-tasks/"+work.task()+"/decisions",Map.of("version",work.version(),"leaseToken",work.token(),"decision","APPROVE","reason","Published evidence rules satisfied"),"p6.reviewer");}
    String order(Work work){return text("SELECT id FROM reward_order WHERE report_id=?",work.report());}
    RewardEvents.Signal signal(Work work){return new RewardEvents.Signal(text("SELECT event_id FROM outbox_event WHERE aggregate_id=? AND event_type='REWARD_RESERVED'",work.report()),"REWARD_RESERVED",work.report(),order(work));}
    Reply withdraw(String key,BigDecimal amount)throws Exception{return api("POST","/withdrawals",Map.of("amount",amount,"regionCode","NORTH"),"p6.owner",key);}
    void assertReconciled(){assertThat(reconciliation.sweep(0,0).checks()).allMatch(LedgerReconciliation.Check::matches);}
    void send(RewardEvents.Signal signal){
        var props=new MessageProperties();props.setMessageId(signal.eventId());props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setContentType("application/json");
        var body=Map.of("schemaVersion",1,"eventId",signal.eventId(),"eventType",signal.type(),"reportId",signal.reportId(),"orderId",signal.orderId());
        rabbit.send(NS+".events","reward.reserved",new Message(JSONObjectUtils.toJSONString(body).getBytes(StandardCharsets.UTF_8),props));
    }
    long depth(String suffix){var p=admin.getQueueProperties(NS+suffix);return p==null?-1:((Number)p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();}
    void start(String id){listeners.getListenerContainer(id).start();}
    void stop(){for(var container:listeners.getListenerContainers())if(container.isRunning())container.stop();}
    void purge(){QUEUES.forEach(q->admin.purgeQueue(NS+q,false));}
    void removeFaults(){for(var fault:List.of(Map.entry("reward_order","p6_test_order_failure"),Map.entry("wallet_account","p6_test_wallet_failure"),Map.entry("wallet_entry","p6_test_entry_failure"))){
        if(count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name=? AND table_name=?",fault.getValue(),fault.getKey())>0)
            jdbc.execute("ALTER TABLE "+fault.getKey()+" DROP CHECK "+fault.getValue());}}
    void clear(){for(String table:List.of("notification","withdrawal_order","wallet_entry","reward_activation","reward_correction","reward_order","wallet_account","budget_entry",
            "review_appeal","review_approval_intent","review_decision","review_audit","review_task","message_inbox","detection_operation","detection_attempt","outbox_event","detection_task",
            "traffic_report","evidence","campaign","identity_user_region","identity_user_role","identity_role_permission","identity_permission","identity_role","identity_user","identity_region"))jdbc.update("DELETE FROM "+table);}
    long insert(String sql,Object...args){var key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    String text(String sql,Object...args){return jdbc.queryForObject(sql,String.class,args);}
    BigDecimal decimal(String sql){return jdbc.queryForObject(sql,BigDecimal.class);}
    Reply api(String method,String path,Map<String,?> body,String user)throws Exception{return api(method,path,body,user,null);}
    Reply api(String method,String path,Map<String,?> body,String user,String key)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("Authorization","Bearer "+bearer(user));
        if(key!=null)request.header("Idempotency-Key",key);
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body(),response.body().startsWith("{")?JSONObjectUtils.parse(response.body()):Map.of());
    }
    String bearer(String user)throws Exception{
        if(tokens.containsKey(user))return tokens.get(user);
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username",user,"password",PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);String token=(String)JSONObjectUtils.parse(response.body()).get("accessToken");
        sessions.add("traffic:auth:session:"+SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));tokens.put(user,token);return token;
    }
    static com.rabbitmq.client.ConnectionFactory factory(){var f=new com.rabbitmq.client.ConnectionFactory();f.setHost("127.0.0.1");f.setPort(25672);f.setUsername("traffic_app");f.setPassword("Traffic_mq_local_only_2026");f.setAutomaticRecoveryEnabled(false);return f;}
    static void ok(Reply reply){assertThat(reply.status()).withFailMessage("HTTP %s: %s",reply.status(),reply.body()).isEqualTo(200);}
    static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(50);}throw new AssertionError("P6 condition timed out");}
    static <T>List<T> parallel(int size,CheckedAction<T> action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(size)){var latch=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();for(int i=0;i<size;i++){int index=i;futures.add(pool.submit(()->{latch.await();return action.call(index);}));}
            latch.countDown();var results=new ArrayList<T>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;}
    }
    interface CheckedAction<T>{T call(int index)throws Exception;}
    record Work(long report,String task,long version,String token){}
    record Reply(int status,String body,Map<String,Object> json){}
}
