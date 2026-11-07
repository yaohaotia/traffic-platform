package com.yao.traffic;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.yao.traffic.messaging.OutboxPublisher;
import com.yao.traffic.detection.DetectionTasks;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/** P8: real HTTP -> MySQL/Redis -> RabbitMQ -> actual RPTC weights -> independent review -> wallet/notification. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndTest {
    static final String NS="traffic-p8-it-"+UUID.randomUUID().toString().substring(0,8),PASSWORD="P8-end-to-end-password-2026!";
    static final int MODEL_PORT=freePort();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired OutboxPublisher publisher;
    @Autowired DetectionTasks detection;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired RabbitAdmin admin;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Map<String,String> tokens=new HashMap<>();final Set<String> sessions=new HashSet<>();Process model;String hash;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry p){
        p.add("traffic.messaging.enabled",()->true);p.add("traffic.messaging.namespace",()->NS);p.add("traffic.messaging.listener-enabled",()->false);p.add("traffic.messaging.polling-enabled",()->false);
        p.add("traffic.review.listener-enabled",()->false);p.add("traffic.review.polling-enabled",()->false);p.add("traffic.reward.enabled",()->true);p.add("traffic.reward.listener-enabled",()->false);p.add("traffic.reward.polling-enabled",()->false);
        p.add("traffic.detection.model-url",()->"http://127.0.0.1:"+MODEL_PORT+"/api/v1/detect");p.add("traffic.detection.retry-base-ms",()->100);
        p.add("traffic.storage.local-root",()->Path.of("target/p8-evidence").toAbsolutePath().toString());
    }
    @BeforeAll void initialize()throws Exception{hash=passwords.encode(PASSWORD);startModel();}
    @BeforeEach void fixtures(){
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("traffic_platform_it");stopListeners();admin.initialize();purge();clear();tokens.clear();
        for(var u:Map.of(12101L,"p8.owner",12102L,"p8.primary",12103L,"p8.rechecker",12104L,"p8.manager",12105L,"p8.other").entrySet())jdbc.update("INSERT INTO identity_user(id,username,password_hash,enabled,auth_version) VALUES(?,?,?,TRUE,0)",u.getKey(),u.getValue(),hash);
        jdbc.update("INSERT INTO identity_region(id,code,name) VALUES(12201,'NORTH','North campus')");
        for(long user:List.of(12101L,12102L,12103L,12104L,12105L))jdbc.update("INSERT INTO identity_user_region(user_id,region_id) VALUES(?,12201)",user);
        jdbc.update("INSERT INTO identity_role(id,code) VALUES(12301,'P8_USER'),(12302,'P8_PRIMARY'),(12303,'P8_RECHECK'),(12304,'P8_MANAGER')");
        jdbc.update("INSERT INTO identity_user_role(user_id,role_id) VALUES(12101,12301),(12105,12301),(12102,12302),(12103,12303),(12104,12304)");
        var permissions=List.of("report:submit","report:read","wallet:read","wallet:withdraw","notification:read","review:claim","review:decide","review:recheck","report:review","identity:user:manage","campaign:manage","reward:manage","withdrawal:manage");
        for(int i=0;i<permissions.size();i++)jdbc.update("INSERT INTO identity_permission(id,code) VALUES(?,?)",12401+i,permissions.get(i));
        for(int p=12401;p<=12405;p++)jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(12301,?)",p);
        for(long role:List.of(12302L,12303L))for(int p:List.of(12406,12407,12409))jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(?,?)",role,p);
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(12303,12408)");
        for(int p:List.of(12402,12403,12410,12411,12412,12413))jdbc.update("INSERT INTO identity_role_permission(role_id,permission_id) VALUES(12304,?)",p);
    }
    @AfterEach void cleanup()throws Exception{stopListeners();clear();purge();sessions.forEach(redis::delete);sessions.clear();
        Path root=Path.of("target/p8-evidence").toAbsolutePath().normalize();if(Files.isDirectory(root))try(var files=Files.list(root)){for(var path:files.toList())if(path.getFileName().toString().matches("[0-9a-f-]{36}\\.png"))Files.deleteIfExists(path);}}
    @AfterAll void finish()throws Exception{stopModel();for(String q:List.of(".detection",".completed",".dead",".review.decided",".reward",".notifications"))admin.deleteQueue(NS+q);admin.deleteExchange(NS+".events");admin.deleteExchange(NS+".dead.exchange");}

    @Test void completeRealModelIndependentReviewRewardNotificationAndSimulatedWithdrawalChain()throws Exception{
        long campaign=campaign();byte[] image=png();long evidence=upload(image);String key=UUID.randomUUID().toString();
        var submission=Map.of("campaignId",campaign,"evidenceId",evidence,"location","North entrance","description","Road obstruction evidence","captureTime",Instant.now().minusSeconds(60).toString());
        Reply submitted=api("POST","/reports",submission,"p8.owner",key);ok(submitted);long report=num(submitted.json().get("id"));
        assertThat(num(api("POST","/reports",submission,"p8.owner",key).json().get("id"))).isEqualTo(report);
        startListeners();pump(()->count("SELECT COUNT(*) FROM review_task WHERE report_id=?",report)==1);
        String expected="rptc-sha256-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("../PatchCraft（算法）/weights/RPTC.pth"))));
        assertThat(text("SELECT model_version FROM detection_task WHERE report_id=?",report)).isEqualTo(expected);
        double score=jdbc.queryForObject("SELECT result_score FROM detection_task WHERE report_id=?",Double.class,report);assertThat(score).isBetween(0.0,1.0);
        String task=text("SELECT id FROM review_task WHERE report_id=?",report);
        if("PRIMARY".equals(text("SELECT stage FROM review_task WHERE id=?",task))){var claim=claim(task,"p8.primary");ok(decide(task,claim,"REQUEST_RECHECK","Independent review requested","p8.primary"));}
        assertThat(api("POST","/review-tasks/"+task+"/claim",Map.of("expectedVersion",version(task)),"p8.primary",null).status()).isEqualTo(403);
        var claim=claim(task,"p8.rechecker");ok(decide(task,claim,"APPROVE","Evidence meets the campaign rules","p8.rechecker"));
        pump(()->"CREDITED".equals(text("SELECT reward_status FROM traffic_report WHERE id=?",report))&&count("SELECT COUNT(*) FROM notification WHERE user_id=12101")>=3);
        Reply wallet=api("GET","/wallet",null,"p8.owner",null);ok(wallet);assertThat(new java.math.BigDecimal(wallet.json().get("available").toString())).isEqualByComparingTo("3.50");
        assertThat((List<?>)api("GET","/management/rewards",null,"p8.manager",null).json().get("items")).hasSize(1);
        assertThat(api("GET","/management/rewards",null,"p8.owner",null).status()).isEqualTo(403);
        Reply withdrawal=api("POST","/withdrawals",Map.of("amount",1.00,"regionCode","NORTH"),"p8.owner","p8-withdraw");ok(withdrawal);
        ok(api("POST","/withdrawals/"+withdrawal.json().get("id")+"/decisions",Map.of("decision","APPROVE","reason","Simulated settlement validation"),"p8.manager",null));
        assertThat(api("GET","/wallet/reconciliation",null,"p8.owner",null).json().get("matches")).isEqualTo(true);
        assertThat(api("GET","/campaigns/"+campaign+"/reconciliation",null,"p8.manager",null).json().get("matches")).isEqualTo(true);
        String notice=text("SELECT id FROM notification WHERE user_id=12101 LIMIT 1");ok(api("POST","/notifications/"+notice+"/read",Map.of(),"p8.owner",null));
        assertThat(count("SELECT COUNT(*) FROM reward_order")).isEqualTo(1);assertThat(count("SELECT COUNT(*) FROM wallet_entry WHERE kind='CREDIT'")).isEqualTo(1);
    }
    @Test void actualModelServiceRestartRecoversFailedDetectionWithoutDuplicatingBusinessState()throws Exception{
        long campaign=campaign(),evidence=upload(png());Reply reply=api("POST","/reports",Map.of("campaignId",campaign,"evidenceId",evidence,"location","Gate","description","Restart recovery evidence","captureTime",Instant.now().minusSeconds(60).toString()),"p8.owner","model-restart");ok(reply);long report=num(reply.json().get("id"));
        stopModel();try{startListeners();pump(()->"RETRY_WAIT".equals(text("SELECT status FROM detection_task WHERE report_id=?",report)));}
        finally{startModel();}
        pump(()->{detection.recoverAndEnqueue();return "SUCCEEDED".equals(text("SELECT status FROM detection_task WHERE report_id=?",report));});
        assertThat(count("SELECT COUNT(*) FROM traffic_report")).isEqualTo(1);assertThat(count("SELECT COUNT(*) FROM detection_attempt WHERE task_id=(SELECT id FROM detection_task WHERE report_id=?)",report)).isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM reward_order")).isZero();
    }
    @Test void controlledAccountProvisioningRevokesOldAccessAndNeverReturnsPasswordHash()throws Exception{
        var input=Map.of("username","p8.created","initialPassword",PASSWORD,"roleIds",List.of(12301),"regionCodes",List.of("NORTH"));
        assertThat(api("POST","/identity/users",input,"p8.owner",null).status()).isEqualTo(403);
        Reply created=api("POST","/identity/users",input,"p8.manager",null);ok(created);long id=num(created.json().get("id"));String old=bearer("p8.created");
        assertThat(api("GET","/identity/users",null,"p8.created",null).status()).isEqualTo(403);
        ok(api("PUT","/identity/users/"+id+"/access",Map.of("roleIds",List.of(12302),"regionCodes",List.of("NORTH")),"p8.manager",null));
        assertThat(withToken("GET","/auth/me",null,old,null).status()).isEqualTo(401);tokens.remove("p8.created");
        Reply me=api("GET","/auth/me",null,"p8.created",null);ok(me);assertThat(((List<?>)me.json().get("permissions")).contains("review:claim")).isTrue();
        Reply list=api("GET","/identity/users",null,"p8.manager",null);ok(list);assertThat(list.body()).doesNotContain(PASSWORD,"password_hash","$2");
        assertThat(text("SELECT password_hash FROM identity_user WHERE id=?",id)).startsWith("$2");assertThat(count("SELECT COUNT(*) FROM identity_audit WHERE target_id=?",id)).isEqualTo(2);
    }
    @Test void legacyPreviewAndExplicitImportPreserveCountsAndDoNotCreateMoney()throws Exception{
        Map<String,Object> bundle=bundle(false);Reply preview=api("POST","/migration/preview",bundle,"p8.manager",null);ok(preview);assertThat(preview.json().get("canImport")).isEqualTo(true);
        assertThat(num(preview.json().get("reportCount"))).isEqualTo(5);assertThat(count("SELECT COUNT(*) FROM legacy_report_archive")).isZero();
        var input=Map.of("bundle",bundle,"userMappings",Map.of("old-user",12101),"expectedSha256",preview.json().get("sourceSha256"),"reason","Counts and user mapping reviewed");
        Reply imported=api("POST","/migration/import",input,"p8.manager",null);ok(imported);ok(api("POST","/migration/import",input,"p8.manager",null));
        assertThat(count("SELECT COUNT(*) FROM legacy_report_archive")).isEqualTo(5);assertThat(count("SELECT COUNT(*) FROM legacy_import_batch")).isEqualTo(1);
        assertThat(text("SELECT mapped_state FROM legacy_report_archive WHERE legacy_id='old-report-1'")).isEqualTo("LEGACY_RECHECK_REQUIRED");
        assertThat(((List<?>)api("GET","/legacy-reports",null,"p8.owner",null).json().get("items"))).hasSize(5);
        assertThat(((List<?>)api("GET","/legacy-reports",null,"p8.other",null).json().get("items"))).isEmpty();
        for(String table:List.of("reward_order","wallet_entry","traffic_report","budget_entry"))assertThat(count("SELECT COUNT(*) FROM "+table)).isZero();
    }
    @Test void legacyImportRejectsUnauthorizedTamperedAndConflictingArchivesAtomically()throws Exception{
        var bundle=bundle(false);assertThat(api("POST","/migration/preview",bundle,"p8.owner",null).status()).isEqualTo(403);
        var preview=api("POST","/migration/preview",bundle,"p8.manager",null);var bad=Map.of("bundle",bundle,"userMappings",Map.of("old-user",12101),"expectedSha256","0".repeat(64),"reason","Invalid hash");
        assertThat(api("POST","/migration/import",bad,"p8.manager",null).status()).isEqualTo(409);
        ok(api("POST","/migration/import",Map.of("bundle",bundle,"userMappings",Map.of("old-user",12101),"expectedSha256",preview.json().get("sourceSha256"),"reason","First confirmed batch"),"p8.manager",null));
        var changed=bundle(true);var next=api("POST","/migration/preview",changed,"p8.manager",null);
        assertThat(api("POST","/migration/import",Map.of("bundle",changed,"userMappings",Map.of("old-user",12101),"expectedSha256",next.json().get("sourceSha256"),"reason","Conflicting old record"),"p8.manager",null).status()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM legacy_import_batch")).isEqualTo(1);assertThat(count("SELECT COUNT(*) FROM legacy_report_archive")).isEqualTo(5);
    }
    @Test void actualWeightsProtocolIsDeterministicAndRetainsThresholdDirection()throws Exception{
        byte[] image=png();var a=postImage("http://127.0.0.1:"+MODEL_PORT+"/api/v1/detect",image,null);var b=postImage("http://127.0.0.1:"+MODEL_PORT+"/api/v1/detect",image,null);
        assertThat(a.statusCode()).isEqualTo(200);assertThat(b.statusCode()).isEqualTo(200);var first=JSONObjectUtils.parse(a.body());var second=JSONObjectUtils.parse(b.body());
        double score=((Number)first.get("score")).doubleValue(),threshold=((Number)first.get("threshold")).doubleValue();
        assertThat(second.get("score")).isEqualTo(first.get("score"));assertThat(first.get("isAiGenerated")).isEqualTo(score>threshold);
        assertThat(first.get("modelVersion").toString()).startsWith("rptc-sha256-");
    }
    @Test void archivalImportDoesNotReactivateADisabledMappedAccount()throws Exception{
        jdbc.update("UPDATE identity_user SET enabled=FALSE WHERE id=12105");var bundle=bundle(false);
        Reply preview=api("POST","/migration/preview",bundle,"p8.manager",null);ok(preview);
        ok(api("POST","/migration/import",Map.of("bundle",bundle,"userMappings",Map.of("old-user",12105),"expectedSha256",preview.json().get("sourceSha256"),"reason","Preserve disabled legacy identity"),"p8.manager",null));
        assertThat(count("SELECT COUNT(*) FROM identity_user WHERE id=12105 AND enabled=FALSE")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM wallet_entry")).isZero();assertThat(count("SELECT COUNT(*) FROM legacy_report_archive WHERE user_id=12105")).isEqualTo(5);
    }

    private Map<String,Object> bundle(boolean changed){var rows=new ArrayList<Map<String,Object>>();for(int state=0;state<5;state++)rows.add(Map.of("legacyId","old-report-"+state,"userLegacyId","old-user","status",state,"location","Original gate","description",changed?"Changed archive":"Original archive"));return Map.of("schemaVersion",1,"users",List.of(Map.of("legacyId","old-user","username","legacy.owner")),"reports",rows);}
    private long campaign()throws Exception{Reply created=api("POST","/campaigns",Map.of("title","End-to-end campaign","regionCode","NORTH","startsAt",Instant.now().minusSeconds(3600).toString(),"endsAt",Instant.now().plusSeconds(3600).toString(),"rewardAmount",3.50,"budgetAmount",100.00),"p8.manager",null);ok(created);long id=num(created.json().get("id"));ok(api("POST","/campaigns/"+id+"/publish",Map.of(),"p8.manager",null));return id;}
    private long upload(byte[] bytes)throws Exception{var response=postImage("http://127.0.0.1:"+port+"/api/v1/evidence",bytes,bearer("p8.owner"));assertThat(response.statusCode()).isEqualTo(200);return num(JSONObjectUtils.parse(response.body()).get("id"));}
    private HttpResponse<String> postImage(String url,byte[] bytes,String token)throws Exception{String boundary="p8-"+UUID.randomUUID();var body=new ByteArrayOutputStream();body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"evidence.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type","multipart/form-data; boundary="+boundary);if(token!=null)request.header("Authorization","Bearer "+token);return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());}
    private Map<String,Object> claim(String id,String actor)throws Exception{Reply result=api("POST","/review-tasks/"+id+"/claim",Map.of("expectedVersion",version(id)),actor,null);ok(result);return result.json();}
    private Reply decide(String id,Map<String,Object> claim,String decision,String reason,String actor)throws Exception{return api("POST","/review-tasks/"+id+"/decisions",Map.of("version",claim.get("version"),"leaseToken",claim.get("leaseToken"),"decision",decision,"reason",reason),actor,null);}
    private long version(String id){return count("SELECT version FROM review_task WHERE id=?",id);}
    private void startListeners(){for(String id:List.of("trafficDetectionListener","trafficReviewCreationListener","trafficRewardListener","trafficNotificationListener"))listeners.getListenerContainer(id).start();}
    private void stopListeners(){for(var container:listeners.getListenerContainers())if(container.isRunning())container.stop();}
    private void purge(){for(String q:List.of(".detection",".completed",".dead",".review.decided",".reward",".notifications"))admin.purgeQueue(NS+q,false);}
    private void clear(){for(String table:List.of("legacy_report_archive","legacy_user_mapping","legacy_import_batch","identity_audit","ledger_drift","maintenance_checkpoint","notification","withdrawal_order","wallet_entry","reward_activation","reward_correction","reward_order","wallet_account","budget_entry","review_appeal","review_approval_intent","review_decision","review_audit","review_task","message_inbox","detection_operation","detection_attempt","outbox_event","detection_task","traffic_report","evidence","campaign","identity_user_region","identity_user_role","identity_role_permission","identity_permission","identity_role","identity_user","identity_region"))jdbc.update("DELETE FROM "+table);}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}private String text(String sql,Object...args){return jdbc.queryForObject(sql,String.class,args);}
    private Reply api(String method,String path,Map<String,?> body,String user,String key)throws Exception{return withToken(method,path,body,bearer(user),key);}
    private Reply withToken(String method,String path,Map<String,?> body,String token,String key)throws Exception{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(body))).build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),response.body(),response.body().startsWith("{")?JSONObjectUtils.parse(response.body()):Map.of());}
    private String bearer(String username)throws Exception{if(tokens.containsKey(username))return tokens.get(username);var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSONObjectUtils.toJSONString(Map.of("username",username,"password",PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);String token=(String)JSONObjectUtils.parse(response.body()).get("accessToken");sessions.add("traffic:auth:session:"+SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("sid"));tokens.put(username,token);return token;}
    private void pump(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(50).toNanos();while(System.nanoTime()<deadline){publisher.publishPending();if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Full workflow did not converge");}
    private void startModel()throws Exception{if(model!=null&&model.isAlive())return;var builder=new ProcessBuilder("D:/soft/Anaconda/python.exe","-u","-m","service.runtime","--host","127.0.0.1","--port",Integer.toString(MODEL_PORT));builder.directory(Path.of("../PatchCraft（算法）").toFile());builder.environment().put("PYTHONPATH",Path.of("../../.cache/python-model").toAbsolutePath().normalize().toString());builder.environment().put("PYTHONIOENCODING","utf-8");builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("target/p8-real-model.log").toFile()));model=builder.start();long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();while(System.nanoTime()<deadline){if(!model.isAlive())throw new AssertionError("Real RPTC process exited; inspect target/p8-real-model.log");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+MODEL_PORT+"/api/v1/detect")).GET().timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==405)return;}catch(Exception unavailable){Thread.sleep(100);}}throw new AssertionError("Real model startup timed out");}
    private void stopModel()throws Exception{if(model!=null){model.destroy();if(!model.waitFor(5,TimeUnit.SECONDS))model.destroyForcibly().waitFor(5,TimeUnit.SECONDS);model=null;}}
    private byte[] png()throws Exception{var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(new java.awt.Color(120,135,145));g.fillRect(0,0,512,512);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
    private static int freePort(){try(var s=new java.net.ServerSocket(0)){return s.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    private static long num(Object value){return ((Number)value).longValue();}private static void ok(Reply reply){assertThat(reply.status()).withFailMessage("HTTP %s: %s",reply.status(),reply.body()).isEqualTo(200);}
    record Reply(int status,String body,Map<String,Object> json){}
}
