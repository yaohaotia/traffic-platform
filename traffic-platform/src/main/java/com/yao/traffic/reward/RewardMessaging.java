package com.yao.traffic.reward;

import com.rabbitmq.client.Channel;
import com.yao.traffic.messaging.MessagingSettings;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
public class RewardMessaging {
    @Bean Declarables rewardDeclarations(MessagingSettings messaging){
        var exchange=new DirectExchange(messaging.exchange(),true,false);
        var rewards=QueueBuilder.durable(messaging.namespace+".reward").build();
        var notifications=QueueBuilder.durable(messaging.namespace+".notifications").build();
        return new Declarables(rewards,notifications,
                BindingBuilder.bind(rewards).to(exchange).with("reward.reserved"),
                BindingBuilder.bind(notifications).to(exchange).with("review.decided"),
                BindingBuilder.bind(notifications).to(exchange).with("reward.reserved"),
                BindingBuilder.bind(notifications).to(exchange).with("reward.changed"));
    }

    @Component
    @ConditionalOnProperty(prefix="traffic.reward",name="enabled",havingValue="true")
    public static class Listener {
        private final RewardEvents events;
        private final RabbitTemplate rabbit;
        private final MessagingSettings messaging;
        public Listener(RewardEvents events,RabbitTemplate rabbit,MessagingSettings messaging){this.events=events;this.rabbit=rabbit;this.messaging=messaging;}
        @RabbitListener(id="trafficRewardListener",queues="${traffic.messaging.namespace:traffic}.reward",ackMode="MANUAL",autoStartup="${traffic.reward.listener-enabled:false}")
        public void reward(Message message,Channel channel)throws IOException{handle(message,channel,false);}
        @RabbitListener(id="trafficNotificationListener",queues="${traffic.messaging.namespace:traffic}.notifications",ackMode="MANUAL",autoStartup="${traffic.reward.listener-enabled:false}")
        public void notification(Message message,Channel channel)throws IOException{handle(message,channel,true);}
        private void handle(Message message,Channel channel,boolean notification)throws IOException{
            long tag=message.getMessageProperties().getDeliveryTag();RewardEvents.Signal signal;
            try{signal=events.parse(message.getBody(),message.getMessageProperties().getMessageId());}
            catch(RuntimeException invalid){reject(message,channel,tag);return;}
            final boolean accepted;
            try{accepted=events.process(signal,notification);}
            catch(RuntimeException unavailable){backoff();channel.basicNack(tag,false,true);return;}
            if(accepted)channel.basicAck(tag,false);else reject(message,channel,tag);
        }
        private void reject(Message source,Channel channel,long tag)throws IOException{
            var properties=new MessageProperties();properties.setMessageId(UUID.randomUUID().toString());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setHeader("reward-rejection-code","INVALID_REWARD_EVENT");
            byte[] body=source.getBody();
            if(body.length>16384)body="{\"errorCode\":\"OVERSIZED_REWARD_EVENT\"}".getBytes(StandardCharsets.UTF_8);
            boolean confirmed=false;var correlation=new CorrelationData(UUID.randomUUID().toString());
            try{
                rabbit.convertAndSend(messaging.deadExchange(),"dead",new Message(body,properties),correlation);
                confirmed=correlation.getFuture().get(messaging.confirmTimeoutMs,TimeUnit.MILLISECONDS).ack()&&correlation.getReturned()==null;
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(Exception unavailable){/* Preserve the original message if quarantine cannot be confirmed. */}
            if(confirmed)channel.basicNack(tag,false,false);else{backoff();channel.basicNack(tag,false,true);}
        }
        private void backoff(){LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));}
    }
}
