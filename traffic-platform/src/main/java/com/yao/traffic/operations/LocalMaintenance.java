package com.yao.traffic.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="traffic.scheduling",name="mode",havingValue="local",matchIfMissing=true)
public class LocalMaintenance {
    private static final Logger LOG=LoggerFactory.getLogger(LocalMaintenance.class);
    private final MaintenanceTasks tasks;
    public LocalMaintenance(MaintenanceTasks tasks){this.tasks=tasks;}
    @Scheduled(fixedDelayString="${traffic.scheduling.local-delay-ms:5000}")
    public void tick(){for(var job:MaintenanceTasks.Job.values())try{tasks.run(job);}catch(RuntimeException failure){LOG.warn("Local maintenance {} failed; see dependency health and backlog",job);}}
}
