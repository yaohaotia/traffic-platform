package com.yao.traffic.review;

import com.rabbitmq.client.Channel;
import com.yao.traffic.messaging.MessagingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Component
@ConditionalOnProperty(prefix="traffic.messaging",name="enabled",havingValue="true")
public class ReviewListener {
    private static final Logger LOG=LoggerFactory.getLogger(ReviewListener.class);
    private final ReviewCompletionConsumer consumer;
    private final ObjectMapper json;
    private final RabbitTemplate rabbit;
    private final MessagingSettings messaging;
    private final ReviewSettings settings;
    private final AtomicLong lastWarning=new AtomicLong();
    public ReviewListener(ReviewCompletionConsumer consumer,ObjectMapper json,RabbitTemplate rabbit,
                          MessagingSettings messaging,ReviewSettings settings){
        this.consumer=consumer;this.json=json;this.rabbit=rabbit;this.messaging=messaging;this.settings=settings;
    }

    @RabbitListener(id="trafficReviewCreationListener",queues="${traffic.messaging.namespace:traffic}.completed",
            ackMode="MANUAL",autoStartup="${traffic.review.listener-enabled:false}")
    public void onMessage(Message message,Channel channel)throws IOException{
        long tag=message.getMessageProperties().getDeliveryTag();ReviewEvent event;
        try{
            if(message.getBody().length>16384)throw new IllegalArgumentException("Oversized event");
            var tree=json.readTree(message.getBody());
            if(!tree.isObject()||!tree.path("schemaVersion").isInt()||tree.path("schemaVersion").intValue()!=1
                    ||!tree.path("reportId").isIntegralNumber()||!tree.path("evidenceId").isIntegralNumber()
                    ||!tree.path("userId").isIntegralNumber())throw new IllegalArgumentException("Invalid event");
            event=json.treeToValue(tree,ReviewEvent.class);
            String messageId=message.getMessageProperties().getMessageId();
            if(!event.valid()||(messageId!=null&&!messageId.equals(event.eventId())))throw new IllegalArgumentException("Invalid event");
        }catch(RuntimeException invalid){quarantineOrRequeue(message,channel,tag);return;}
        final ReviewCompletionConsumer.Outcome outcome;
        try{outcome=consumer.process(event);}
        catch(RuntimeException unavailable){
            warnThrottled();backoff();channel.basicNack(tag,false,true);return;
        }
        // The consumer owns its transaction; it has committed before ACK can happen.
        if(outcome==ReviewCompletionConsumer.Outcome.ACK)channel.basicAck(tag,false);
        else quarantineOrRequeue(message,channel,tag);
    }

    private void quarantineOrRequeue(Message source,Channel channel,long tag)throws IOException{
        if(confirmedDeadLetter(source))channel.basicNack(tag,false,false);
        else{warnThrottled();backoff();channel.basicNack(tag,false,true);}
    }
    private boolean confirmedDeadLetter(Message source){
        var properties=new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("review-rejection-code","INVALID_COMPLETION_EVENT");
        byte[] body=source.getBody();
        if(body.length>16384){
            String hash=com.yao.traffic.evidence.application.EvidenceService.sha256(body);
            body=("{\"errorCode\":\"OVERSIZED_COMPLETION_EVENT\",\"payloadSha256\":\""+hash+"\"}").getBytes(StandardCharsets.UTF_8);
        }
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        try{
            rabbit.convertAndSend(messaging.deadExchange(),"dead",new Message(body,properties),correlation);
            var result=correlation.getFuture().get(messaging.confirmTimeoutMs,TimeUnit.MILLISECONDS);
            return result.ack()&&correlation.getReturned()==null;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(Exception unavailable){return false;}
    }
    private void backoff(){LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(settings.dependencyBackoffMs));}
    private void warnThrottled(){
        long now=System.currentTimeMillis(),last=lastWarning.get();
        if(now-last>5000&&lastWarning.compareAndSet(last,now))LOG.warn("Review delivery retained for retry; required persistence or quarantine is unavailable");
    }
}
