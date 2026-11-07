package com.yao.traffic.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All methods hold only short database transactions; broker I/O belongs to OutboxPublisher. */
@Service
public class OutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MessagingSettings settings;
    public OutboxStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MessagingSettings settings) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.settings=settings;
    }
    public List<Delivery> claim(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Outbox batch must be 1..100");
        return tx.execute(status -> {
            var rows=jdbc.query("""
                    SELECT event_id,event_type,payload FROM outbox_event
                    WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6)
                    ORDER BY next_attempt_at,created_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs,n)->new Delivery(rs.getString(1),rs.getString(2),rs.getString(3),UUID.randomUUID().toString()),limit);
            for (Delivery row:rows) jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHING',lease_token=?,
                    lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE event_id=?
                    """,row.leaseToken(),settings.outboxLeaseSeconds,row.eventId());
            return rows;
        });
    }
    public boolean markPublished(String eventId,String leaseToken) {
        return jdbc.update("""
                UPDATE outbox_event SET status='SENT',published_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,
                last_error_code=NULL,last_error_message=NULL,updated_at=CURRENT_TIMESTAMP(6)
                WHERE event_id=? AND status='PUBLISHING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
                """,eventId,leaseToken)==1;
    }
    public boolean markFailed(String eventId,String leaseToken,String code) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var attempts=jdbc.query("""
                    SELECT attempt_count FROM outbox_event WHERE event_id=? AND status='PUBLISHING' AND lease_token=? FOR UPDATE
                    """,(rs,n)->rs.getInt(1),eventId,leaseToken);
            if(attempts.isEmpty())return false;
            int count=attempts.getFirst();
            String safe=code!=null && code.matches("[A-Z0-9_]{1,80}")?code:"PUBLISH_FAILED";
            long delay=Math.min(60000,1000L*(1L<<Math.min(16,Math.max(0,count-1))));
            return jdbc.update("""
                    UPDATE outbox_event SET status=?,lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),
                    last_error_code=?,last_error_message=?,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE event_id=? AND status='PUBLISHING' AND lease_token=?
                    """,count>=settings.maxPublishAttempts?"DEAD":"PENDING",delay*1000L,
                    safe,safe,eventId,leaseToken)==1;
        }));
    }
    public int recoverExpired() {
        var expired=jdbc.query("""
                SELECT event_id,lease_token FROM outbox_event WHERE status='PUBLISHING'
                AND lease_until<=CURRENT_TIMESTAMP(6) ORDER BY lease_until LIMIT 100
                """,(rs,n)->new String[]{rs.getString(1),rs.getString(2)});
        int count=0;
        for(var row:expired) if(markFailed(row[0],row[1],"PUBLISH_LEASE_EXPIRED")) count++;
        return count;
    }
    public record Delivery(String eventId,String eventType,String payload,String leaseToken) { }
}
