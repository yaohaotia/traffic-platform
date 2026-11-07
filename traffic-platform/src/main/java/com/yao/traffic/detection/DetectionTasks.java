package com.yao.traffic.detection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database state machine. Every entry point commits before returning; no external I/O here. */
@Service
public class DetectionTasks {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DetectionSettings settings;
    private final ObjectMapper json;
    public DetectionTasks(JdbcTemplate jdbc,PlatformTransactionManager manager,DetectionSettings settings,ObjectMapper json) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.settings=settings; this.json=json;
    }
    public Optional<DetectionModels.Claim> claim(DetectionModels.Event event) {
        if(event==null || !event.valid())throw new InvalidEvent();
        return tx.execute(status->{
            var rows=jdbc.query("""
                    SELECT t.id,t.report_id,t.status,t.version,t.attempt_count,r.evidence_id,r.user_id,e.object_key,r.review_status
                    FROM detection_task t JOIN traffic_report r ON r.id=t.report_id JOIN evidence e ON e.id=r.evidence_id
                    WHERE t.id=? FOR UPDATE
                    """,(rs,n)->new Task(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getInt(5),
                    rs.getLong(6),rs.getLong(7),rs.getString(8),rs.getString(9)),event.taskId());
            if(rows.isEmpty())throw new InvalidEvent();
            Task task=rows.getFirst();
            if(task.reportId()!=event.reportId() || task.evidenceId()!=event.evidenceId() || task.userId()!=event.userId())
                throw new InvalidEvent();
            Integer source=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND aggregate_type='REPORT' AND aggregate_id=? AND event_type=?
                    """,Integer.class,event.eventId(),event.reportId(),event.eventType());
            if(source==null || source!=1)throw new InvalidEvent();
            if(!"PENDING".equals(task.status()) || !"WAITING_DETECTION".equals(task.reviewStatus()))return Optional.empty();
            if(task.attempts()>=settings.maxAttempts) {
                deadWithoutClaim(task,"ATTEMPTS_EXHAUSTED");return Optional.empty();
            }
            String token=UUID.randomUUID().toString(); long version=task.version()+1;
            int updated=jdbc.update("""
                    UPDATE detection_task SET status='RUNNING',version=version+1,attempt_count=attempt_count+1,lease_token=?,
                    lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),last_signal_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND status='PENDING' AND version=?
                    """,token,settings.leaseSeconds,task.id(),task.version());
            if(updated!=1)return Optional.empty();
            jdbc.update("INSERT INTO detection_attempt(task_id,version,lease_token,outcome) VALUES(?,?,?,'RUNNING')",task.id(),version,token);
            jdbc.update("UPDATE traffic_report SET detection_status='RUNNING' WHERE id=?",task.reportId());
            return Optional.of(new DetectionModels.Claim(task.id(),task.reportId(),task.evidenceId(),task.objectKey(),version,token));
        });
    }
    public boolean succeed(DetectionModels.Claim claim,DetectionModels.Result result) {
        if(result==null || !result.valid())throw new IllegalArgumentException("Invalid detection result");
        return Boolean.TRUE.equals(tx.execute(status->{
            int changed=jdbc.update("""
                    UPDATE detection_task SET status='SUCCEEDED',model_version=?,result_score=?,result_threshold=?,
                    completed_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,last_error_code=NULL,last_error_message=NULL,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND status='RUNNING' AND version=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
                    """,result.modelVersion(),result.score(),result.threshold(),claim.taskId(),claim.version(),claim.leaseToken());
            if(changed==0)return false;
            int report=jdbc.update("""
                    UPDATE traffic_report SET detection_status='SUCCEEDED',review_status=?
                    WHERE id=? AND review_status='WAITING_DETECTION'
                    """,result.isAiGenerated()?"RISK_REVIEW":"WAITING_REVIEW",claim.reportId());
            if(report!=1)throw new IllegalStateException("Review state changed during detection");
            finishAttempt(claim.taskId(),claim.version(),"SUCCEEDED",null);
            appendEvent(claim.taskId(),claim.reportId(),"DETECTION_COMPLETED");
            return true;
        }));
    }
    public boolean fail(DetectionModels.Claim claim,String code,boolean retryable) {
        return Boolean.TRUE.equals(tx.execute(status->{
            var counts=jdbc.query("""
                    SELECT attempt_count FROM detection_task WHERE id=? AND status='RUNNING' AND version=? AND lease_token=?
                    AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE
                    """,(rs,n)->rs.getInt(1),claim.taskId(),claim.version(),claim.leaseToken());
            if(counts.isEmpty())return false;
            boolean retry=retryable && counts.getFirst()<settings.maxAttempts;
            String safe=safeCode(code);
            jdbc.update("""
                    UPDATE detection_task SET status=?,next_attempt_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                    lease_token=NULL,lease_until=NULL,last_error_code=?,last_error_message=?,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE id=? AND version=? AND lease_token=?
                    """,retry?"RETRY_WAIT":"DEAD",settings.retryDelayMs(counts.getFirst())*1000L,safe,safe,
                    claim.taskId(),claim.version(),claim.leaseToken());
            jdbc.update("UPDATE traffic_report SET detection_status=? WHERE id=? AND review_status='WAITING_DETECTION'",
                    retry?"RETRY_WAIT":"DEAD",claim.reportId());
            finishAttempt(claim.taskId(),claim.version(),retry?"RETRY_WAIT":"DEAD",safe);
            if(!retry)appendEvent(claim.taskId(),claim.reportId(),"DETECTION_DEAD");
            return true;
        }));
    }
    public int recoverAndEnqueue() {
        return tx.execute(status->{
            int changed=0;
            var expired=jdbc.query("""
                    SELECT id,report_id,version,attempt_count FROM detection_task
                    WHERE status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)
                    ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    """,(rs,n)->new Expired(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getInt(4)));
            for(var task:expired) {
                boolean retry=task.attempts()<settings.maxAttempts;
                jdbc.update("""
                        UPDATE detection_task SET status=?,lease_token=NULL,lease_until=NULL,
                        next_attempt_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),last_error_code='EXECUTION_LEASE_EXPIRED',
                        last_error_message='EXECUTION_LEASE_EXPIRED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                        """,retry?"RETRY_WAIT":"DEAD",settings.retryDelayMs(task.attempts())*1000L,task.id());
                jdbc.update("UPDATE traffic_report SET detection_status=? WHERE id=? AND review_status='WAITING_DETECTION'",
                        retry?"RETRY_WAIT":"DEAD",task.reportId());
                finishAttempt(task.id(),task.version(),"LEASE_EXPIRED","EXECUTION_LEASE_EXPIRED");
                if(!retry)appendEvent(task.id(),task.reportId(),"DETECTION_DEAD");
                changed++;
            }
            var due=jdbc.query("""
                    SELECT t.id,t.report_id FROM detection_task t JOIN traffic_report r ON r.id=t.report_id
                    WHERE t.status='RETRY_WAIT' AND t.next_attempt_at<=CURRENT_TIMESTAMP(6) AND r.review_status='WAITING_DETECTION'
                    ORDER BY t.next_attempt_at,t.id LIMIT 100 FOR UPDATE SKIP LOCKED
                    """,(rs,n)->new Ref(rs.getString(1),rs.getLong(2)));
            for(var task:due) {
                jdbc.update("UPDATE detection_task SET status='PENDING',last_signal_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",task.id());
                jdbc.update("UPDATE traffic_report SET detection_status='PENDING' WHERE id=?",task.reportId());
                appendEvent(task.id(),task.reportId(),"DETECTION_REQUESTED");changed++;
            }
            var pending=jdbc.query("""
                    SELECT t.id,t.report_id FROM detection_task t JOIN traffic_report r ON r.id=t.report_id
                    WHERE t.status='PENDING' AND r.review_status='WAITING_DETECTION'
                    AND t.last_signal_at<=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))
                    AND NOT EXISTS(SELECT 1 FROM outbox_event o WHERE o.aggregate_id=t.report_id AND o.aggregate_type='REPORT'
                    AND o.event_type IN ('REPORT_SUBMITTED','DETECTION_REQUESTED') AND o.status IN ('PENDING','PUBLISHING','DEAD'))
                    AND EXISTS(SELECT 1 FROM outbox_event sent WHERE sent.aggregate_id=t.report_id AND sent.aggregate_type='REPORT'
                    AND sent.event_type IN ('REPORT_SUBMITTED','DETECTION_REQUESTED') AND sent.status='SENT')
                    ORDER BY t.last_signal_at,t.id LIMIT 100 FOR UPDATE SKIP LOCKED
                    """,(rs,n)->new Ref(rs.getString(1),rs.getLong(2)),-settings.pendingResignalSeconds);
            for(var task:pending) {
                jdbc.update("UPDATE detection_task SET last_signal_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",task.id());
                appendEvent(task.id(),task.reportId(),"DETECTION_REQUESTED");changed++;
            }
            return changed;
        });
    }
    /** Used within an administration transaction after task/report row locks and authorization. */
    void appendEvent(String taskId,long reportId,String type) {
        var source=jdbc.queryForMap("SELECT evidence_id,user_id FROM traffic_report WHERE id=?",reportId);
        String id=UUID.randomUUID().toString();
        var payload=new LinkedHashMap<String,Object>();
        payload.put("schemaVersion",1);payload.put("eventId",id);payload.put("eventType",type);payload.put("taskId",taskId);
        payload.put("reportId",reportId);payload.put("evidenceId",source.get("evidence_id"));payload.put("userId",source.get("user_id"));
        jdbc.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,attempt_count)
                VALUES(?,'REPORT',?,?,?,'PENDING',0)
                """,id,reportId,type,json.writeValueAsString(payload));
    }
    private void deadWithoutClaim(Task task,String code) {
        jdbc.update("UPDATE detection_task SET status='DEAD',last_error_code=?,last_error_message=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",code,code,task.id());
        jdbc.update("UPDATE traffic_report SET detection_status='DEAD' WHERE id=? AND review_status='WAITING_DETECTION'",task.reportId());
        appendEvent(task.id(),task.reportId(),"DETECTION_DEAD");
    }
    private void finishAttempt(String id,long version,String outcome,String code) {
        jdbc.update("UPDATE detection_attempt SET outcome=?,error_code=?,finished_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND version=?",outcome,code,id,version);
    }
    private static String safeCode(String code){return code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"DETECTION_FAILED";}
    private record Task(String id,long reportId,String status,long version,int attempts,long evidenceId,long userId,String objectKey,String reviewStatus){ }
    private record Expired(String id,long reportId,long version,int attempts){ }
    private record Ref(String id,long reportId){ }
    public static class InvalidEvent extends RuntimeException { }
}
