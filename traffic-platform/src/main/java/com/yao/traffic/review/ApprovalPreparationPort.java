package com.yao.traffic.review;

import java.math.BigDecimal;

/** P6 must implement budget/order preparation inside the caller's existing database transaction. */
public interface ApprovalPreparationPort {
    void prepare(ApprovedDecision decision);
    record ApprovedDecision(String decisionId,String taskId,long taskVersion,long reportId,
                            long reviewerId,long campaignId,long userId,BigDecimal amount) { }
}
