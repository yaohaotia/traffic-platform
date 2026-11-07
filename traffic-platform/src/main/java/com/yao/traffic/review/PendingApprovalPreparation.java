package com.yao.traffic.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** P5 records intent only. There is deliberately no budget reservation or wallet mutation. */
@Component
public class PendingApprovalPreparation implements ApprovalPreparationPort {
    private final JdbcTemplate jdbc;
    public PendingApprovalPreparation(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void prepare(ApprovedDecision decision){
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Approval preparation requires the existing review transaction");
        int changed=jdbc.update("""
                INSERT INTO review_approval_intent(id,decision_id,report_id,task_id,task_version,user_id,campaign_id,amount,status)
                VALUES(?,?,?,?,?,?,?,?,'AWAITING_REWARD_INTEGRATION')
                """,UUID.randomUUID().toString(),decision.decisionId(),decision.reportId(),decision.taskId(),
                decision.taskVersion(),decision.userId(),decision.campaignId(),decision.amount());
        if(changed!=1)throw new IllegalStateException("Approval intent was not saved");
    }
}
