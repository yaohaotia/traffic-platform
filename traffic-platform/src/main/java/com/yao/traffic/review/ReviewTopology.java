package com.yao.traffic.review;

import com.yao.traffic.messaging.MessagingSettings;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P4's completed queue is unchanged; only a new durable decision parking queue is declared. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="traffic.messaging",name="enabled",havingValue="true")
public class ReviewTopology {
    @Bean Declarables reviewDeclarations(MessagingSettings messaging){
        var exchange=new DirectExchange(messaging.exchange(),true,false);
        var decisions=QueueBuilder.durable(messaging.namespace+".review.decided").build();
        return new Declarables(decisions,BindingBuilder.bind(decisions).to(exchange).with("review.decided"));
    }
}
