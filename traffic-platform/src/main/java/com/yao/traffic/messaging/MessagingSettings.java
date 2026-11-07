package com.yao.traffic.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessagingSettings {
    public final String namespace;
    public final long confirmTimeoutMs;
    public final int outboxLeaseSeconds;
    public final int maxPublishAttempts;
    public MessagingSettings(@Value("${traffic.messaging.namespace:traffic}") String namespace,
            @Value("${traffic.messaging.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${traffic.messaging.outbox-lease-seconds:30}") int outboxLeaseSeconds,
            @Value("${traffic.messaging.max-publish-attempts:10}") int maxPublishAttempts) {
        if (!namespace.matches("[A-Za-z0-9._-]{1,80}") || confirmTimeoutMs < 100 || outboxLeaseSeconds * 1000L <= confirmTimeoutMs
                || maxPublishAttempts < 1 || maxPublishAttempts > 100) throw new IllegalArgumentException("Invalid messaging limits");
        this.namespace=namespace; this.confirmTimeoutMs=confirmTimeoutMs;
        this.outboxLeaseSeconds=outboxLeaseSeconds; this.maxPublishAttempts=maxPublishAttempts;
    }
    public String exchange() { return namespace + ".events"; }
    public String detectionQueue() { return namespace + ".detection"; }
    public String completedQueue() { return namespace + ".completed"; }
    public String deadExchange() { return namespace + ".dead.exchange"; }
    public String deadQueue() { return namespace + ".dead"; }
}
