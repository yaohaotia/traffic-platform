package com.yao.traffic.operations;

import com.yao.traffic.detection.DetectionTasks;
import com.yao.traffic.messaging.OutboxPublisher;
import com.yao.traffic.messaging.OutboxStore;
import com.yao.traffic.review.ReviewCompletionConsumer;
import com.yao.traffic.review.ReviewTasks;
import com.yao.traffic.reward.RewardEvents;
import com.yao.traffic.reward.LedgerReconciliation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class MaintenanceTasks {
    public enum Job{OUTBOX,DETECTION,REVIEW,REWARD,RECONCILIATION}
    private final MaintenanceCoordinator coordinator;
    private final OutboxStore outbox;
    private final OutboxPublisher publisher;
    private final DetectionTasks detection;
    private final ReviewTasks reviews;
    private final ReviewCompletionConsumer completions;
    private final RewardEvents rewards;
    private final LedgerReconciliation reconciliation;
    private final JdbcTemplate jdbc;
    public MaintenanceTasks(MaintenanceCoordinator coordinator,OutboxStore outbox,OutboxPublisher publisher,DetectionTasks detection,
            ReviewTasks reviews,ReviewCompletionConsumer completions,RewardEvents rewards,LedgerReconciliation reconciliation,JdbcTemplate jdbc){
        this.coordinator=coordinator;this.outbox=outbox;this.publisher=publisher;this.detection=detection;this.reviews=reviews;
        this.completions=completions;this.rewards=rewards;this.reconciliation=reconciliation;this.jdbc=jdbc;
    }
    public boolean run(Job job){return coordinator.run(job.name(),()->{
        switch(job){
            case OUTBOX->{outbox.recoverExpired();publisher.publishPending();}
            case DETECTION->detection.recoverAndEnqueue();
            case REVIEW->{reviews.recoverExpired();completions.recoverMissing();}
            case REWARD->{rewards.recoverCredits();rewards.recoverNotifications();}
            case RECONCILIATION->reconcile();
        }
    });}
    private void reconcile(){
        var checkpoints=jdbc.queryForList("SELECT first_cursor,second_cursor FROM maintenance_checkpoint WHERE job_name='RECONCILIATION'");
        long first=0,second=0;if(!checkpoints.isEmpty()){first=((Number)checkpoints.getFirst().get("first_cursor")).longValue();second=((Number)checkpoints.getFirst().get("second_cursor")).longValue();}
        var sweep=reconciliation.sweep(first,second);
        for(var check:sweep.checks())jdbc.update("INSERT INTO ledger_drift(target_type,target_id,active) VALUES(?,?,?) ON DUPLICATE KEY UPDATE active=?,last_checked_at=CURRENT_TIMESTAMP(6)",check.type(),check.id(),!check.matches(),!check.matches());
        jdbc.update("INSERT INTO maintenance_checkpoint(job_name,first_cursor,second_cursor) VALUES('RECONCILIATION',?,?) ON DUPLICATE KEY UPDATE first_cursor=?,second_cursor=?",
                sweep.hasMore()?sweep.nextCampaign():0,sweep.hasMore()?sweep.nextUser():0,sweep.hasMore()?sweep.nextCampaign():0,sweep.hasMore()?sweep.nextUser():0);
        if(sweep.checks().stream().anyMatch(c->!c.matches()))throw new IllegalStateException("Ledger reconciliation detected drift; automatic repair is disabled");
    }
}
