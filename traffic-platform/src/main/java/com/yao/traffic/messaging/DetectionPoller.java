package com.yao.traffic.messaging;

import com.yao.traffic.detection.DetectionTasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(prefix="traffic.messaging",name={"enabled","polling-enabled"},havingValue="true")
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="false",matchIfMissing=true)
public class DetectionPoller {
    private static final Logger LOG=LoggerFactory.getLogger(DetectionPoller.class);
    private final DetectionTasks tasks;
    private final OutboxStore outbox;
    private final OutboxPublisher publisher;
    public DetectionPoller(DetectionTasks tasks,OutboxStore outbox,OutboxPublisher publisher){this.tasks=tasks;this.outbox=outbox;this.publisher=publisher;}
    @Scheduled(fixedDelayString="${traffic.messaging.poll-delay-ms:1000}")
    public void tick(){
        try{outbox.recoverExpired();tasks.recoverAndEnqueue();publisher.publishPending();}
        catch(RuntimeException failure){LOG.warn("Detection maintenance will retry on next cycle",failure);}
    }
}
