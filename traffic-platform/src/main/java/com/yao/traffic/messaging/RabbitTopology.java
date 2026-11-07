package com.yao.traffic.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="traffic.messaging",name="enabled",havingValue="true")
public class RabbitTopology {
    @Bean
    Declarables detectionTopology(MessagingSettings settings) {
        var exchange=new DirectExchange(settings.exchange(),true,false);
        var deadExchange=new DirectExchange(settings.deadExchange(),true,false);
        var queue=QueueBuilder.durable(settings.detectionQueue()).deadLetterExchange(settings.deadExchange())
                .deadLetterRoutingKey("dead").build();
        var completed=QueueBuilder.durable(settings.completedQueue()).build();
        var dead=QueueBuilder.durable(settings.deadQueue()).build();
        return new Declarables(exchange,deadExchange,queue,completed,dead,
                BindingBuilder.bind(queue).to(exchange).with("detection.requested"),
                BindingBuilder.bind(completed).to(exchange).with("detection.completed"),
                BindingBuilder.bind(dead).to(exchange).with("detection.dead"),
                BindingBuilder.bind(dead).to(deadExchange).with("dead"));
    }
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor mandatoryRabbitTemplate() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(bean instanceof RabbitTemplate template) template.setMandatory(true);
                return bean;
            }
        };
    }
}
