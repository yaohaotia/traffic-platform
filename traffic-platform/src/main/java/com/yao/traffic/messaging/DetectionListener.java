package com.yao.traffic.messaging;

import com.rabbitmq.client.Channel;
import com.yao.traffic.detection.DetectionModels;
import com.yao.traffic.detection.DetectionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

@Component
@ConditionalOnProperty(prefix="traffic.messaging",name="enabled",havingValue="true")
public class DetectionListener {
    private static final Logger LOG=LoggerFactory.getLogger(DetectionListener.class);
    private final DetectionProcessor processor;
    private final ObjectMapper json;
    public DetectionListener(DetectionProcessor processor,ObjectMapper json){this.processor=processor;this.json=json;}
    @RabbitListener(id="trafficDetectionListener",queues="${traffic.messaging.namespace:traffic}.detection",
            ackMode="MANUAL",autoStartup="${traffic.messaging.listener-enabled:true}")
    public void onMessage(Message message,Channel channel)throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        DetectionModels.Event event;
        try {
            if(message.getBody().length>16384)throw new IllegalArgumentException("Oversized event");
            var tree=json.readTree(message.getBody());
            if(!tree.isObject()||!tree.path("schemaVersion").isInt()||tree.path("schemaVersion").intValue()!=1||!tree.path("reportId").isIntegralNumber()
                    ||!tree.path("evidenceId").isIntegralNumber()||!tree.path("userId").isIntegralNumber())throw new IllegalArgumentException("Invalid event");
            event=json.treeToValue(tree,DetectionModels.Event.class);
            String messageId=message.getMessageProperties().getMessageId();
            if(!event.valid()||(messageId!=null&&!messageId.equals(event.eventId())))throw new IllegalArgumentException("Invalid event");
        }catch(RuntimeException invalid){channel.basicNack(tag,false,false);return;}
        try {
            if(processor.process(event)==DetectionProcessor.Outcome.ACK)channel.basicAck(tag,false);
            else channel.basicNack(tag,false,false);
        }catch(RuntimeException dependencyFailure){
            // No ACK for uncommitted state. DB task recovery re-signals after service restoration.
            LOG.warn("Detection delivery could not persist; rejecting signal taskId={}",event.taskId(),dependencyFailure);
            channel.basicNack(tag,false,false);
        }
    }
}
