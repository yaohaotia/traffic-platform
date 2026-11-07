package com.yao.traffic.reward;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Source validation prevents fabricated messages from creating money or user-visible statements. */
@Service
public class RewardEvents {
    private final RewardLedger ledger;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public RewardEvents(RewardLedger ledger,ObjectMapper json,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.ledger=ledger;this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);
    }

    public boolean process(Signal signal,boolean notification){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Consumer owns its transaction");
        String consumer=notification?"notification-v1":"reward-credit-v1";
        return Boolean.TRUE.equals(tx.execute(status->{
            var sources=jdbc.queryForList("SELECT event_type,aggregate_id,payload FROM outbox_event WHERE event_id=?",signal.eventId());
            if(sources.isEmpty())return false;
            var source=sources.getFirst();var canonical=json.readTree((String)source.get("payload"));
            if(!signal.type().equals(source.get("event_type"))||((Number)source.get("aggregate_id")).longValue()!=signal.reportId()
                    ||!signal.eventId().equals(canonical.path("eventId").asText())||canonical.path("reportId").asLong()!=signal.reportId()
                    ||!signal.type().equals(canonical.path("eventType").asText())||canonical.path("schemaVersion").asInt()!=1)return false;
            boolean review=List.of("REVIEW_APPROVED","REVIEW_REJECTED","REVIEW_ESCALATED").contains(signal.type());
            boolean reward=List.of("REWARD_RESERVED","REWARD_CREDITED","REWARD_CORRECTED").contains(signal.type());
            if(!review&&!reward)return false;
            if(!notification&&(!"REWARD_RESERVED".equals(signal.type())||signal.orderId()==null))return false;
            if(reward){
                if(signal.orderId()==null||!signal.orderId().equals(canonical.path("orderId").asText()))return false;
                var orders=jdbc.queryForList("SELECT report_id FROM reward_order WHERE id=?",Long.class,signal.orderId());
                if(orders.isEmpty()||orders.getFirst()!=signal.reportId())return false;
            }
            // Shared report lock makes delivery and correction order deterministic.
            var owners=jdbc.queryForList("SELECT user_id FROM traffic_report WHERE id=? FOR UPDATE",Long.class,signal.reportId());
            if(owners.isEmpty())return false;
            // INSERT IGNORE avoids absent-key gap-lock upgrades between unrelated concurrent reports.
            // This fixed, validated row commits only together with the business effect below.
            if(jdbc.update("INSERT IGNORE INTO message_inbox(consumer_name,event_id,aggregate_id,outcome) VALUES(?,?,?,'PROCESSED')",consumer,signal.eventId(),signal.reportId())==0)return true;
            if(notification){
                String title=switch(signal.type()){
                    case "REVIEW_APPROVED"->"线索审核通过";case "REVIEW_REJECTED"->"线索审核未通过";
                    case "REVIEW_ESCALATED"->"线索已进入复核";case "REWARD_CREDITED"->"线索奖励已入账";
                    case "REWARD_CORRECTED"->"线索奖励已更正";default->"线索奖励待入账";
                };
                jdbc.update("INSERT INTO notification(id,user_id,source_event_id,title,body) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),owners.getFirst(),
                        signal.eventId(),title,"线索 #"+signal.reportId()+"："+title+"。请在记录中查看处理详情。");
            }else ledger.credit(signal.orderId());
            return true;
        }));
    }

    public int recoverCredits(){
        var events=jdbc.queryForList("""
                SELECT o.payload FROM outbox_event o JOIN reward_order r ON r.report_id=o.aggregate_id
                WHERE o.event_type='REWARD_RESERVED' AND o.status='SENT' AND r.state='RESERVED'
                ORDER BY o.created_at LIMIT 100
                """,String.class);
        int count=0;for(String payload:events)if(process(parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),null),false))count++;
        return count;
    }

    public int recoverNotifications(){
        var events=jdbc.queryForList("""
                SELECT o.payload FROM outbox_event o WHERE o.status='SENT'
                AND o.event_type IN ('REVIEW_APPROVED','REVIEW_REJECTED','REVIEW_ESCALATED','REWARD_RESERVED','REWARD_CREDITED','REWARD_CORRECTED')
                AND NOT EXISTS(SELECT 1 FROM message_inbox i WHERE i.consumer_name='notification-v1' AND i.event_id=o.event_id)
                ORDER BY o.created_at LIMIT 100
                """,String.class);
        int count=0;for(String payload:events)if(process(parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),null),true))count++;
        return count;
    }

    Signal parse(byte[] body,String messageId){
        if(body.length>16384)throw new IllegalArgumentException("Oversized event");
        var node=json.readTree(body);String id=node.path("eventId").asText();
        if(!node.isObject()||!node.path("schemaVersion").isInt()||node.path("schemaVersion").intValue()!=1
                ||!node.path("reportId").isIntegralNumber()||node.path("reportId").asLong()<=0
                ||!uuid(id)||(messageId!=null&&!messageId.equals(id)))throw new IllegalArgumentException("Invalid event");
        String order=node.path("orderId").asText(null);if(order!=null&&!uuid(order))throw new IllegalArgumentException("Invalid order");
        return new Signal(id,node.path("eventType").asText(),node.path("reportId").asLong(),order);
    }
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException bad){return false;}}
    public record Signal(String eventId,String type,long reportId,String orderId){}
}
