package com.yao.traffic.operations;

import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** Watchdog lock reduces duplicated batches; database CAS/unique keys remain the correctness boundary. */
@Service
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class MaintenanceCoordinator {
    private final RedissonClient redis;
    private final String namespace;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    public MaintenanceCoordinator(RedissonClient redis,@Value("${traffic.messaging.namespace:traffic}")String namespace,JdbcTemplate jdbc,MeterRegistry meters){
        this.redis=redis;this.namespace=namespace;this.jdbc=jdbc;this.meters=meters;
    }
    public boolean run(String name,Runnable action){
        if(!name.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("Invalid maintenance job");
        var lock=redis.getLock("traffic:"+namespace+":maintenance:"+name);boolean acquired=false;long started=System.nanoTime();
        try{
            acquired=lock.tryLock(0,TimeUnit.MILLISECONDS);
            if(!acquired){meters.counter("traffic.maintenance.runs","job",name,"outcome","skipped").increment();return false;}
            action.run();
            jdbc.update("INSERT INTO maintenance_checkpoint(job_name,last_success,last_result) VALUES(?,CURRENT_TIMESTAMP(6),'SUCCESS') ON DUPLICATE KEY UPDATE last_success=CURRENT_TIMESTAMP(6),last_result='SUCCESS'",name);
            meters.counter("traffic.maintenance.runs","job",name,"outcome","success").increment();return true;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Maintenance interrupted",interrupted);}
        catch(RuntimeException failure){
            meters.counter("traffic.maintenance.runs","job",name,"outcome","failure").increment();
            try{jdbc.update("INSERT INTO maintenance_checkpoint(job_name,last_failure,last_result) VALUES(?,CURRENT_TIMESTAMP(6),'FAILED') ON DUPLICATE KEY UPDATE last_failure=CURRENT_TIMESTAMP(6),last_result='FAILED'",name);}
            catch(RuntimeException persistenceUnavailable){/* Original failure remains visible to the scheduler. */}
            throw failure;
        }finally{
            meters.timer("traffic.maintenance.duration","job",name).record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
            if(acquired&&lock.isHeldByCurrentThread())lock.unlock();
        }
    }
}
