package com.yao.traffic.reward;

import com.yao.traffic.review.ApprovalPreparationPort;
import com.yao.traffic.review.PendingApprovalPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Runtime replacement for the staged P5 intent-only adapter. Uses the caller's transaction. */
@Component
@Primary
@ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
public class RewardApprovalPreparation implements ApprovalPreparationPort {
    private final PendingApprovalPreparation intents;
    private final RewardLedger ledger;
    public RewardApprovalPreparation(PendingApprovalPreparation intents,RewardLedger ledger){this.intents=intents;this.ledger=ledger;}
    @Override public void prepare(ApprovedDecision decision){
        intents.prepare(decision);
        ledger.reserve(decision);
    }
}
