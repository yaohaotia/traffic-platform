package com.yao.traffic.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisher {
    private final OutboxStore outbox;
    private final RabbitTemplate rabbit;
    private final MessagingSettings settings;
    public OutboxPublisher(OutboxStore outbox,RabbitTemplate rabbit,MessagingSettings settings) {
        this.outbox=outbox; this.rabbit=rabbit; this.settings=settings;
    }
    public int publishPending() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Broker I/O must run outside a database transaction");
        int sent=0;
        for(int batch=0;batch<20;batch++) {
            var claimed=outbox.claim(1);
            if(claimed.isEmpty())break;
            var delivery=claimed.getFirst();
            String route=switch(delivery.eventType()) {
                case "REPORT_SUBMITTED","DETECTION_REQUESTED" -> "detection.requested";
                case "DETECTION_COMPLETED" -> "detection.completed";
                case "DETECTION_DEAD" -> "detection.dead";
                case "REVIEW_APPROVED","REVIEW_REJECTED","REVIEW_ESCALATED" -> "review.decided";
                case "REWARD_RESERVED" -> "reward.reserved";
                case "REWARD_CREDITED","REWARD_CORRECTED" -> "reward.changed";
                default -> null;
            };
            if(route==null) { outbox.markFailed(delivery.eventId(),delivery.leaseToken(),"UNSUPPORTED_EVENT_TYPE"); continue; }
            var properties=new MessageProperties();
            properties.setMessageId(delivery.eventId());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            var correlation=new CorrelationData(delivery.eventId()+":"+delivery.leaseToken());
            try {
                rabbit.convertAndSend(settings.exchange(),route,
                        new Message(delivery.payload().getBytes(StandardCharsets.UTF_8),properties),correlation);
                var confirm=correlation.getFuture().get(settings.confirmTimeoutMs,TimeUnit.MILLISECONDS);
                if(confirm.ack() && correlation.getReturned()==null) {
                    if(outbox.markPublished(delivery.eventId(),delivery.leaseToken())) sent++;
                } else outbox.markFailed(delivery.eventId(),delivery.leaseToken(),
                        correlation.getReturned()!=null?"PUBLISH_UNROUTABLE":"PUBLISH_NACK");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outbox.markFailed(delivery.eventId(),delivery.leaseToken(),"PUBLISH_INTERRUPTED");
                break;
            } catch(Exception exception) {
                outbox.markFailed(delivery.eventId(),delivery.leaseToken(),"PUBLISH_UNCONFIRMED");
            }
        }
        return sent;
    }
}
