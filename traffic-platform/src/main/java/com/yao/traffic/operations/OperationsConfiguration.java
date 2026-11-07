package com.yao.traffic.operations;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="traffic.operations",name="enabled",havingValue="true")
public class OperationsConfiguration {
    @Bean(destroyMethod="shutdown") RedissonClient maintenanceRedis(
            @Value("${spring.data.redis.host:127.0.0.1}")String host,@Value("${spring.data.redis.port:6379}")int port,
            @Value("${spring.data.redis.username:}")String username,@Value("${spring.data.redis.password:}")String password,
            @Value("${spring.data.redis.ssl.enabled:false}")boolean ssl){
        var config=new Config();config.setCodec(StringCodec.INSTANCE);config.setThreads(2);config.setNettyThreads(2);
        config.setLockWatchdogTimeout(30000);
        var server=config.useSingleServer().setAddress((ssl?"rediss://":"redis://")+host+":"+port)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4).setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2).setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(0);
        if(!username.isBlank())server.setUsername(username);if(!password.isBlank())server.setPassword(password);
        return Redisson.create(config);
    }
}
