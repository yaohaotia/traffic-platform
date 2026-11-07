package com.yao.traffic.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewSettings {
    public final int leaseSeconds;
    public final int maxTransfers;
    public final long dependencyBackoffMs;
    public ReviewSettings(@Value("${traffic.review.lease-seconds:1800}")int leaseSeconds,
                          @Value("${traffic.review.max-transfers:3}")int maxTransfers,
                          @Value("${traffic.review.dependency-backoff-ms:100}")long backoff) {
        if(leaseSeconds<5||leaseSeconds>28800||maxTransfers<1||maxTransfers>20||backoff<10||backoff>5000)
            throw new IllegalArgumentException("Invalid review workflow limits");
        this.leaseSeconds=leaseSeconds;this.maxTransfers=maxTransfers;this.dependencyBackoffMs=backoff;
    }
}
