package com.yao.traffic.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Completion events are wakeups; only the database can establish review eligibility. */
@Service
public class ReviewCompletionConsumer {
    public static final String CONSUMER_NAME="review-task-creation-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ReviewCompletionConsumer(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;tx=new TransactionTemplate(manager);}

    public Outcome process(ReviewEvent event){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Review completion must own its commit boundary before ACK");
        if(event==null||!event.valid())return Outcome.REJECT;
        return tx.execute(status->{
            var reports=jdbc.query("""
                    SELECT id,user_id,evidence_id,region_code,detection_status,review_status
                    FROM traffic_report WHERE id=? FOR UPDATE
                    """,(rs,n)->new Source(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6)),event.reportId());
            if(reports.isEmpty())return Outcome.REJECT;
            var report=reports.getFirst();
            if(report.userId()!=event.userId()||report.evidenceId()!=event.evidenceId())return Outcome.REJECT;
            Integer source=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND aggregate_type='REPORT' AND aggregate_id=?
                    AND event_type='DETECTION_COMPLETED'
                    """,Integer.class,event.eventId(),event.reportId());
            if(source==null||source!=1)return Outcome.REJECT;
            // Check history before current workflow state: late redelivery after approval is still an ACK.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM message_inbox WHERE consumer_name=? AND event_id=?",Integer.class,
                    CONSUMER_NAME,event.eventId())!=0)return Outcome.ACK;
            var completed=jdbc.query("SELECT version FROM detection_task WHERE id=? AND report_id=? AND status='SUCCEEDED'",
                    (rs,n)->rs.getLong(1),event.taskId(),event.reportId());
            if(completed.isEmpty()||!"SUCCEEDED".equals(report.detectionStatus()))return Outcome.REJECT;
            var prior=jdbc.queryForList("SELECT id FROM review_task WHERE report_id=? FOR UPDATE",String.class,event.reportId());
            if(!prior.isEmpty()) { inbox(event,"ALREADY_CREATED");return Outcome.ACK; }
            if(!"WAITING_REVIEW".equals(report.reviewStatus())&&!"RISK_REVIEW".equals(report.reviewStatus()))return Outcome.REJECT;
            String task=UUID.randomUUID().toString();
            String stage="RISK_REVIEW".equals(report.reviewStatus())?"RECHECK":"PRIMARY";
            jdbc.update("""
                    INSERT INTO review_task(id,report_id,source_event_id,source_detection_version,region_code,stage,status,version,transfer_count)
                    VALUES(?,?,?,?,?,?,'READY',0,0)
                    """,task,event.reportId(),event.eventId(),completed.getFirst(),report.region(),stage);
            jdbc.update("""
                    INSERT INTO review_audit(task_id,operation,actor_id,from_version,to_version,reason)
                    VALUES(?,'CREATED',NULL,0,0,'DETECTION_COMPLETED')
                    """,task);
            inbox(event,"CREATED");
            return Outcome.ACK;
        });
    }
    /** Recover already-published wakeups without inventing events or bypassing DEAD sources. */
    public int recoverMissing(){
        var candidates=jdbc.query("""
                SELECT o.event_id,t.id,r.id,r.evidence_id,r.user_id FROM traffic_report r
                JOIN detection_task t ON t.report_id=r.id AND t.status='SUCCEEDED'
                JOIN outbox_event o ON o.aggregate_id=r.id AND o.aggregate_type='REPORT'
                    AND o.event_type='DETECTION_COMPLETED' AND o.status='SENT'
                WHERE r.detection_status='SUCCEEDED' AND r.review_status IN ('WAITING_REVIEW','RISK_REVIEW')
                AND NOT EXISTS(SELECT 1 FROM review_task reviewed WHERE reviewed.report_id=r.id)
                ORDER BY o.created_at,o.event_id LIMIT 100
                """,(rs,n)->new ReviewEvent(1,rs.getString(1),"DETECTION_COMPLETED",rs.getString(2),
                rs.getLong(3),rs.getLong(4),rs.getLong(5)));
        int processed=0;
        for(var event:candidates)if(process(event)==Outcome.ACK)processed++;
        return processed;
    }
    private void inbox(ReviewEvent event,String outcome){
        jdbc.update("INSERT INTO message_inbox(consumer_name,event_id,aggregate_id,outcome) VALUES(?,?,?,?)",
                CONSUMER_NAME,event.eventId(),event.reportId(),outcome);
    }
    private record Source(long id,long userId,long evidenceId,String region,String detectionStatus,String reviewStatus){ }
    public enum Outcome{ACK,REJECT}
}
