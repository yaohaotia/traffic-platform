package com.yao.traffic.operations;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="traffic.scheduling",name="mode",havingValue="xxl")
public class XxlMaintenance {
    @Bean XxlJobSpringExecutor trafficExecutor(@Value("${traffic.scheduling.admin-addresses}")String admins,
            @Value("${traffic.scheduling.access-token}")String token,@Value("${traffic.scheduling.executor-port:29999}")int port,
            @Value("${traffic.scheduling.executor-ip:}")String ip,@Value("${traffic.scheduling.executor-address:}")String address,
            @Value("${traffic.scheduling.app-name:traffic-platform}")String app,
            @Value("${traffic.scheduling.log-path:./data/job-logs}")String logPath){
        validate(admins,token,port,app);
        var executor=new XxlJobSpringExecutor();executor.setAdminAddresses(admins);executor.setAccessToken(token);
        executor.setTimeout(3);executor.setEnabled(true);executor.setAppname(app);executor.setIp(ip);executor.setAddress(address);
        executor.setPort(port);executor.setLogPath(logPath);executor.setLogRetentionDays(7);return executor;
    }
    public static void validate(String admins,String token,int port,String app){
        if(token==null||token.length()<32||token.contains("default_token"))throw new IllegalArgumentException("Explicit non-default XXL-JOB token with at least 32 characters required");
        if(admins==null||admins.isBlank())throw new IllegalArgumentException("XXL-JOB admin addresses required");
        for(String value:admins.split(",")){
            var uri=java.net.URI.create(value.trim());
            if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
                    ||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid XXL-JOB admin address");
        }
        if(port<1024||port>65535||app==null||!app.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid XXL-JOB executor configuration");
    }
    @Bean Handlers maintenanceHandlers(MaintenanceTasks tasks){return new Handlers(tasks);}
    public static class Handlers {
        private final MaintenanceTasks tasks;
        public Handlers(MaintenanceTasks tasks){this.tasks=tasks;}
        @XxlJob("trafficOutboxJob") public void outbox(){run(MaintenanceTasks.Job.OUTBOX);}
        @XxlJob("trafficDetectionRecoveryJob") public void detection(){run(MaintenanceTasks.Job.DETECTION);}
        @XxlJob("trafficReviewRecoveryJob") public void review(){run(MaintenanceTasks.Job.REVIEW);}
        @XxlJob("trafficRewardRecoveryJob") public void reward(){run(MaintenanceTasks.Job.REWARD);}
        @XxlJob("trafficLedgerReconciliationJob") public void reconciliation(){run(MaintenanceTasks.Job.RECONCILIATION);}
        private void run(MaintenanceTasks.Job job){
            try{boolean ran=tasks.run(job);XxlJobHelper.handleSuccess(ran?"Maintenance batch committed":"Another instance owns the maintenance batch");}
            catch(RuntimeException failure){XxlJobHelper.handleFail("Maintenance failed; inspect dependency health, job logs and backlog");throw failure;}
        }
    }
}
