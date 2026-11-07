package com.yao.traffic.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Local operational driver. P7 exposes the same bounded application methods through XXL-JOB. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(prefix="traffic.review",name="polling-enabled",havingValue="true")
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="false",matchIfMissing=true)
public class ReviewPoller {
    private static final Logger LOG=LoggerFactory.getLogger(ReviewPoller.class);
    private final ReviewTasks tasks;
    private final ReviewCompletionConsumer completions;
    public ReviewPoller(ReviewTasks tasks,ReviewCompletionConsumer completions){this.tasks=tasks;this.completions=completions;}
    @Scheduled(fixedDelayString="${traffic.review.poll-delay-ms:5000}")
    public void tick(){
        try{completions.recoverMissing();tasks.recoverExpired();}
        catch(RuntimeException failure){LOG.warn("Review maintenance did not complete; next scheduled cycle will retry");}
    }
}
