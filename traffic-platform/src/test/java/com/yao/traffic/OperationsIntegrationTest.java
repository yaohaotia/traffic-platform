package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.yao.traffic.operations.*;
import com.yao.traffic.evidence.infrastructure.SharedVolumeEvidenceStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/** Real middleware and native XXL executor; the admin protocol peer is explicitly a local test server. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
@ActiveProfiles("integration")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsIntegrationTest {
    static final String NS="traffic-p7-it-"+UUID.randomUUID().toString().substring(0,8),TOKEN="P7-private-xxl-token-longer-than-32-characters",PASSWORD="P7-operations-password-2026!";
    static final int EXECUTOR_PORT=freePort();
    static final List<String> CALLBACKS=new CopyOnWriteArrayList<>();
    static final HttpServer ADMIN=adminPeer();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedissonClient maintenanceRedis;
    @Autowired MaintenanceCoordinator coordinator;
    @Autowired MaintenanceTasks tasks;
    @Autowired com.yao.traffic.reward.RewardEvents rewardEvents;
    @Autowired CampaignCatalog catalog;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder passwords;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Map<String,String> tokens=new HashMap<>();final Set<String> sessions=new HashSet<>();String hash;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("traffic.operations.enabled",()->true);p.add("traffic.messaging.enabled",()->true);p.add("traffic.messaging.namespace",()->NS);
        p.add("traffic.messaging.listener-enabled",()->false);p.add("traffic.messaging.polling-enabled",()->false);
        p.add("traffic.review.listener-enabled",()->false);p.add("traffic.review.polling-enabled",()->false);
        p.add("traffic.reward.enabled",()->true);p.add("traffic.reward.listener-enabled",()->false);p.add("traffic.reward.polling-enabled",()->false);
        p.add("traffic.scheduling.mode",()->"xxl");p.add("traffic.scheduling.admin-addresses",()->"http://127.0.0.1:"+ADMIN.getAddress().getPort());
        p.add("traffic.scheduling.access-token",()->TOKEN);p.add("traffic.scheduling.executor-ip",()->"127.0.0.1");p.add("traffic.scheduling.executor-port",()->EXECUTOR_PORT);
        p.add("traffic.scheduling.log-path",()->Path.of("target/p7-job-logs").toAbsolutePath().toString());
        p.add("management.endpoint.health.group.readiness.include",()->"readinessState,db,redis,rabbit,evidenceStorage");
        p.add("spring.datasource.hikari.connection-timeout",()->5000);
        p.add("spring.datasource.hikari.validation-timeout",()->1000);
    }
    @BeforeAll void initial(){hash=passwords.encode(PASSWORD);}
    @BeforeEach void fixture(){
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("traffic_platform_it");clear();tokens.clear();
        redis.delete("traffic:"+NS+":catalog:campaign:10501");
        for(var user:Map.of(10401L,"p7.owner",10402L,"p7.south",10403L,"p7.manager").entrySet())jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(?,?,?,TRUE,0)",user.getKey(),user.getValue(),hash);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(10601,'P7_USER'),(10602,'P7_MANAGER')");
        jdbc.update("INSERT INTO identity_permission(id,code) VALUES(10701,'wallet:read'),(10702,'operations:read'),(10703,'campaign:manage')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(10401,10601),(10402,10601),(10403,10602)");
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(10601,10701),(10602,10701),(10602,10702),(10602,10703)");
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(10801,'NORTH','North'),(10802,'SOUTH','South')");
        jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(10401,10801),(10403,10801),(10402,10802)");
        jdbc.update("INSERT INTO campaign(id,title,region_code,starts_at,ends_at,reward_amount,budget_amount,state,created_by) VALUES(10501,'P7 campaign','NORTH',?,?,3.50,100.00,'DRAFT',10403)",Timestamp.from(Instant.now().minusSeconds(3600)),Timestamp.from(Instant.now().plusSeconds(3600)));
    }
    @AfterEach void cleanup(){clear();sessions.forEach(redis::delete);sessions.clear();redis.delete("traffic:"+NS+":catalog:campaign:10501");}
    @AfterAll void shutdown(){
        ADMIN.stop(0);
        var factory=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",25672);factory.setUsername("traffic_app");factory.setPassword("Traffic_mq_local_only_2026");
        try{var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(factory);for(String suffix:List.of(".detection",".completed",".dead",".review.decided",".reward",".notifications"))admin.deleteQueue(NS+suffix);admin.deleteExchange(NS+".events");admin.deleteExchange(NS+".dead.exchange");}finally{factory.destroy();}
    }

    @Test void twoIndependentRedisClientsCoordinateOneBatchAndReleaseAfterCompletion()throws Exception{
        var second=newClient();
        try(var pool=Executors.newFixedThreadPool(2)){
            var other=new MaintenanceCoordinator(second,NS,jdbc,meters);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var effects=new AtomicInteger();
            var first=pool.submit(()->coordinator.run("CONTENTION",()->{effects.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try{assertThat(other.run("CONTENTION",effects::incrementAndGet)).isFalse();}finally{release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();assertThat(effects.get()).isEqualTo(1);
            assertThat(other.run("CONTENTION",effects::incrementAndGet)).isTrue();assertThat(effects.get()).isEqualTo(2);
        }finally{second.shutdown();}
    }
    @Test void failedMaintenancePersistsFailureAndDoesNotKeepTheDistributedLock(){
        assertThatThrownBy(()->coordinator.run("FAILURE_TEST",()->{throw new IllegalStateException("Synthetic failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(text("SELECT last_result FROM maintenance_checkpoint WHERE job_name='FAILURE_TEST'")).isEqualTo("FAILED");
        assertThat(coordinator.run("FAILURE_TEST",()->{})).isTrue();
        assertThat(count("SELECT COUNT(*) FROM maintenance_checkpoint WHERE job_name='FAILURE_TEST' AND last_success IS NOT NULL AND last_failure IS NOT NULL")).isEqualTo(1);
    }
    @Test void duplicateRewardMaintenanceAcrossCoordinatorsHasOneBusinessEffect(){
        reservedRewardFixture();assertThat(tasks.run(MaintenanceTasks.Job.REWARD)).isTrue();
        var second=newClient();try{var other=new MaintenanceCoordinator(second,NS,jdbc,meters);
            assertThat(other.run("REWARD",()->{rewardEvents.recoverCredits();rewardEvents.recoverNotifications();})).isTrue();
        }finally{second.shutdown();}
        assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available FROM wallet_account WHERE user_id=10401",BigDecimal.class)).isEqualByComparingTo("3.50");
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);
    }
    @Test void nativeXxlExecutorRejectsWrongTokenAndActuallyRunsRegisteredHandler()throws Exception{
        await(()->{try{return xxl("/beat",Map.of(),TOKEN).code()==200;}catch(Exception e){return false;}});
        assertThat(xxl("/beat",Map.of(),"incorrect-token").code()).isNotEqualTo(200);
        long log=System.currentTimeMillis();var body=new LinkedHashMap<String,Object>();
        body.put("jobId",11001);body.put("executorHandler","trafficOutboxJob");body.put("executorParams","");body.put("executorBlockStrategy","SERIAL_EXECUTION");body.put("executorTimeout",10);
        body.put("logId",log);body.put("logDateTime",log);body.put("glueType","BEAN");body.put("glueUpdatetime",0);body.put("broadcastIndex",0);body.put("broadcastTotal",1);
        assertThat(xxl("/run",body,TOKEN).code()).isEqualTo(200);
        await(()->count("SELECT COUNT(*) FROM maintenance_checkpoint WHERE job_name='OUTBOX' AND last_success IS NOT NULL")==1);
        await(()->CALLBACKS.stream().anyMatch(value->value.contains(Long.toString(log))));
    }
    @Test void invalidXxlCredentialsFailActualBeanStartup(){
        new ApplicationContextRunner().withUserConfiguration(XxlMaintenance.class).withBean(MaintenanceTasks.class,()->tasks)
                .withPropertyValues("traffic.operations.enabled=true","traffic.scheduling.mode=xxl","traffic.scheduling.admin-addresses=http://127.0.0.1:1","traffic.scheduling.access-token=default_token")
                .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);});
    }
    @Test void campaignCacheEvictsOnlyAfterCommitAndEnforcesScopeEvenWhenWarm()throws Exception{
        assertThat(api("GET","/api/v1/campaigns/10501",null,"p7.manager").status()).isEqualTo(200);
        assertThat(redis.getExpire("traffic:"+NS+":catalog:campaign:10501")).isBetween(1L,30L);
        assertThat(api("GET","/api/v1/campaigns/10501",null,"p7.owner").status()).isEqualTo(404);
        ok(api("POST","/api/v1/campaigns/10501/publish",Map.of(),"p7.manager"));
        Reply published=api("GET","/api/v1/campaigns/10501",null,"p7.owner");ok(published);assertThat(published.json().get("state")).isEqualTo("PUBLISHED");
        assertThat(api("GET","/api/v1/campaigns/10501",null,"p7.south").status()).isEqualTo(404);
        ok(api("POST","/api/v1/campaigns/10501/close",Map.of(),"p7.manager"));
        assertThat(api("GET","/api/v1/campaigns/10501",null,"p7.owner").status()).isEqualTo(404);
    }
    @Test void rolledBackCampaignUpdateDoesNotInvalidateOrPublishUncommittedCacheState()throws Exception{
        ok(api("GET","/api/v1/campaigns/10501",null,"p7.manager"));String before=redis.opsForValue().get("traffic:"+NS+":catalog:campaign:10501");
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->{jdbc.update("UPDATE campaign SET state='CLOSED' WHERE id=10501");catalog.invalidateAfterCommit(10501);throw new IllegalStateException("Rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get("traffic:"+NS+":catalog:campaign:10501")).isEqualTo(before);assertThat(text("SELECT state FROM campaign WHERE id=10501")).isEqualTo("DRAFT");
    }
    @Test void openApiAndMetricsRequireOperationsPermissionAndContainImplementedApis()throws Exception{
        assertThat(api("GET","/v3/api-docs",null,"p7.owner").status()).isEqualTo(403);
        Reply spec=api("GET","/v3/api-docs",null,"p7.manager");ok(spec);
        assertThat(spec.body()).contains("/api/v1/reports","/api/v1/review-tasks","/api/v1/wallet","/api/v1/notifications","bearer").doesNotContain(PASSWORD,TOKEN);
        assertThat(api("GET","/actuator/prometheus",null,"p7.owner").status()).isEqualTo(403);
        Reply metric=api("GET","/actuator/prometheus",null,"p7.manager");ok(metric);assertThat(metric.body()).contains("traffic_backlog");
    }
    @Test void reconciliationPublishesActionableDriftAndDoesNotRepairBalance()throws Exception{
        jdbc.update("UPDATE campaign SET budget_reserved=1 WHERE id=10501");
        assertThatThrownBy(()->tasks.run(MaintenanceTasks.Job.RECONCILIATION)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM ledger_drift WHERE active=TRUE")).isEqualTo(1);
        Reply summary=api("GET","/api/v1/operations/summary",null,"p7.manager");ok(summary);assertThat(((Number)summary.json().get("reconciliationDrifts")).intValue()).isEqualTo(1);
        assertThat(api("GET","/api/v1/operations/summary",null,"p7.owner").status()).isEqualTo(403);
        jdbc.update("UPDATE campaign SET budget_reserved=0 WHERE id=10501");assertThat(tasks.run(MaintenanceTasks.Job.RECONCILIATION)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM ledger_drift WHERE active=TRUE")).isZero();
    }
    @Test void explicitSharedVolumeAdapterMakesObjectsVisibleToASecondInstance()throws Exception{
        Path root=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"p7-shared-");
        try{var first=new SharedVolumeEvidenceStorage(root.toString());var second=new SharedVolumeEvidenceStorage(root.toString());
            byte[] bytes={1,2,3};String key=first.write(bytes,"png");assertThat(second.read(key)).containsExactly(bytes);
            assertThatThrownBy(()->second.read("../secrets.png")).isInstanceOf(IllegalArgumentException.class);second.delete(key);assertThat(first.available()).isTrue();
        }finally{Files.deleteIfExists(root);}
        assertThatThrownBy(()->new SharedVolumeEvidenceStorage("relative/evidence")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void redisOutageMakesReadinessAndAuthenticationUnavailableButLivenessStaysUp()throws Exception{
        bearer("p7.owner");
        try{compose("stop","redis");assertThat(publicGet("/actuator/health/liveness")).isEqualTo(200);assertThat(publicGet("/actuator/health/readiness")).isEqualTo(503);
            assertThat(api("GET","/api/v1/auth/me",null,"p7.owner").status()).isEqualTo(503);
        }finally{compose("up","-d","--wait","--wait-timeout","60","redis");await(()->{try{return "PONG".equals(redis.getConnectionFactory().getConnection().ping());}catch(RuntimeException e){return false;}});}
        assertThat(publicGet("/actuator/health/readiness")).isEqualTo(200);ok(api("GET","/api/v1/auth/me",null,"p7.owner"));
    }
    @Test void mysqlOutageMakesReadinessUnavailableAndRecoversWithoutMemoryFallback()throws Exception{
        bearer("p7.owner");
        try{compose("stop","mysql");assertThat(publicGet("/actuator/health/liveness")).isEqualTo(200);assertThat(publicGet("/actuator/health/readiness")).isEqualTo(503);
            assertThat(api("GET","/api/v1/auth/me",null,"p7.owner").status()).isEqualTo(503);
        }finally{compose("up","-d","--wait","--wait-timeout","60","mysql");await(()->{try{return jdbc.queryForObject("SELECT 1",Integer.class)==1;}catch(RuntimeException e){return false;}});}
        assertThat(publicGet("/actuator/health/readiness")).isEqualTo(200);
    }

    private void reservedRewardFixture(){
        String digest="a".repeat(64);long evidence=insert("INSERT INTO evidence(owner_id,object_key,sha256,media_type,size_bytes) VALUES(10401,?,?,'image/png',3)",UUID.randomUUID()+".png",digest);
        long report=insert("INSERT INTO traffic_report(campaign_id,user_id,evidence_id,evidence_sha256,region_code,location,description,capture_time,reward_amount,detection_status,review_status,reward_status,request_key,request_hash) VALUES(10501,10401,?,?,'NORTH','Gate','P7 fixture',?,3.50,'SUCCEEDED','APPROVED','RESERVED',?,?)",evidence,digest,Timestamp.from(Instant.now()),UUID.randomUUID().toString(),digest);
        String task=UUID.randomUUID().toString(),decision=UUID.randomUUID().toString(),order=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO review_task(id,report_id,source_event_id,source_detection_version,region_code,stage,status,version) VALUES(?,?,?,1,'NORTH','PRIMARY','APPROVED',2)",task,report,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO review_decision(id,task_id,task_version,stage,actor_id,decision,reason,lease_token_hash) VALUES(?,?,1,'PRIMARY',10403,'APPROVE','P7 fixture',?)",decision,task,digest);
        jdbc.update("INSERT INTO reward_order(id,report_id,decision_id,campaign_id,user_id,amount,state) VALUES(?,?,?,10501,10401,3.50,'RESERVED')",order,report,decision);
        jdbc.update("UPDATE campaign SET budget_reserved=3.50 WHERE id=10501");jdbc.update("INSERT INTO budget_entry(campaign_id,source_key,kind,reserved_delta,reason) VALUES(10501,?,'RESERVE',3.50,'P7 fixture')",order);
        String payload=JSONObjectUtils.toJSONString(Map.of("schemaVersion",1,"eventId",event,"eventType","REWARD_RESERVED","orderId",order,"reportId",report));
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count) VALUES(?,'REPORT',?,'REWARD_RESERVED',?,'SENT',1)",event,report,payload);
    }
    private RedissonClient newClient(){var c=new Config();c.setThreads(1).setNettyThreads(1);c.useSingleServer().setAddress("redis://127.0.0.1:26379").setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);return Redisson.create(c);}
    private void clear(){for(String table:List.of("ledger_drift","maintenance_checkpoint","notification","withdrawal_order","wallet_entry","reward_activation","reward_correction","reward_order","wallet_account","budget_entry","review_appeal","review_approval_intent","review_decision","review_audit","review_task","message_inbox","detection_operation","detection_attempt","outbox_event","detection_task","traffic_report","evidence","campaign","identity_user_region","identity_user_role","identity_role_permission","identity_permission","identity_role","identity_user","identity_region"))jdbc.update("DELETE FROM "+table);}
    private long insert(String sql,Object...args){var k=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},k);return Objects.requireNonNull(k.getKey()).longValue();}
    private long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    private String text(String sql){return jdbc.queryForObject(sql,String.class);}
    private Reply api(String method,String path,Map<String,?> body,String user)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("Authorization","Bearer "+bearer(user));
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.body(),response.body().startsWith("{")?JSONObjectUtils.parse(response.body()):Map.of());
    }
    private String bearer(String user)throws Exception{
        if(tokens.containsKey(user))return tokens.get(user);var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username",user,"password",PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);String token=(String)JSONObjectUtils.parse(response.body()).get("accessToken");sessions.add("traffic:auth:session:"+SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));tokens.put(user,token);return token;
    }
    private int publicGet(String path)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    private XxlReply xxl(String path,Map<String,?> body,String token)throws Exception{
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+EXECUTOR_PORT+path)).timeout(Duration.ofSeconds(5)).header("XXL-JOB-ACCESS-TOKEN",token).POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new XxlReply(((Number)JSONObjectUtils.parse(response.body()).get("code")).intValue());
    }
    private static int freePort(){try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    private static HttpServer adminPeer(){try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(exchange.getRequestURI().getPath().contains("callback"))CALLBACKS.add(body);byte[] result="{\"code\":200,\"msg\":null,\"data\":null}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,result.length);try(var stream=exchange.getResponseBody()){stream.write(result);}});server.start();return server;}catch(Exception e){throw new IllegalStateException(e);}}
    private static void compose(String...commands)throws Exception{var command=new ArrayList<>(List.of("docker","compose","-f","dev/compose.yml"));command.addAll(List.of(commands));var p=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(!p.waitFor(90,TimeUnit.SECONDS)||p.exitValue()!=0)throw new AssertionError("Dedicated Compose action failed: "+output);}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("P7 condition timed out");}
    private static void ok(Reply response){assertThat(response.status()).withFailMessage("HTTP %s: %s",response.status(),response.body()).isEqualTo(200);}
    record Reply(int status,String body,Map<String,Object> json){}
    record XxlReply(int code){}
}
