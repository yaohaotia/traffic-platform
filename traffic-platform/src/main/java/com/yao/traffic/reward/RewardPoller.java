package com.yao.traffic.reward;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix="traffic.reward",name={"enabled","polling-enabled"},havingValue="true")
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="false",matchIfMissing=true)
public class RewardPoller {
    private static final Logger LOG=LoggerFactory.getLogger(RewardPoller.class);
    private final RewardEvents events;
    public RewardPoller(RewardEvents events){this.events=events;}
    @Scheduled(fixedDelayString="${traffic.reward.poll-interval-ms:5000}")
    public void recover(){try{events.recoverCredits();events.recoverNotifications();}catch(RuntimeException unavailable){LOG.warn("Reward recovery deferred; persistence or messaging is unavailable");}}
}
