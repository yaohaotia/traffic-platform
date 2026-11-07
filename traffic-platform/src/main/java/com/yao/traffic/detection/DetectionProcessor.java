package com.yao.traffic.detection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Independent of Rabbit channel ownership, making acknowledgement boundaries explicit. */
@Service
public class DetectionProcessor {
    private final DetectionTasks tasks;
    private final ModelClient model;
    public DetectionProcessor(DetectionTasks tasks,ModelClient model){this.tasks=tasks;this.model=model;}
    public Outcome process(DetectionModels.Event event) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Detection orchestration must not be called in a surrounding transaction");
        final java.util.Optional<DetectionModels.Claim> claim;
        try { claim=tasks.claim(event); }
        catch(DetectionTasks.InvalidEvent invalid){return Outcome.REJECT;}
        if(claim.isEmpty())return Outcome.ACK;
        try { tasks.succeed(claim.get(),model.detect(claim.get())); }
        catch(ModelClient.Failure failure){tasks.fail(claim.get(),failure.code(),failure.retryable());}
        // Database failure propagates: listener does not ACK an uncommitted result.
        return Outcome.ACK;
    }
    public enum Outcome { ACK, REJECT }
}
