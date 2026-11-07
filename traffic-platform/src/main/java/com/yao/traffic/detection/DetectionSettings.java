package com.yao.traffic.detection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
public class DetectionSettings {
    public final URI modelUrl;
    public final long httpTimeoutMs;
    public final int leaseSeconds;
    public final int maxAttempts;
    public final long retryBaseMs;
    public final int pendingResignalSeconds;
    public DetectionSettings(@Value("${traffic.detection.model-url:http://127.0.0.1:5001/api/v1/detect}") URI modelUrl,
            @Value("${traffic.detection.http-timeout-ms:10000}") long httpTimeoutMs,
            @Value("${traffic.detection.lease-seconds:30}") int leaseSeconds,
            @Value("${traffic.detection.max-attempts:3}") int maxAttempts,
            @Value("${traffic.detection.retry-base-ms:1000}") long retryBaseMs,
            @Value("${traffic.detection.pending-resignal-seconds:60}") int pendingResignalSeconds) {
        if (!("http".equals(modelUrl.getScheme()) || "https".equals(modelUrl.getScheme())) || modelUrl.getHost()==null
                || modelUrl.getUserInfo()!=null || modelUrl.getFragment()!=null || modelUrl.getQuery()!=null
                || httpTimeoutMs<100 || httpTimeoutMs>120000 || leaseSeconds*1000L <= httpTimeoutMs
                || leaseSeconds>600 || maxAttempts<1 || maxAttempts>20 || retryBaseMs<1 || pendingResignalSeconds<1)
            throw new IllegalArgumentException("Invalid model endpoint or detection execution limits");
        this.modelUrl=modelUrl; this.httpTimeoutMs=httpTimeoutMs; this.leaseSeconds=leaseSeconds;
        this.maxAttempts=maxAttempts; this.retryBaseMs=retryBaseMs; this.pendingResignalSeconds=pendingResignalSeconds;
    }
    public long retryDelayMs(int attempt) { return Math.min(60000L, retryBaseMs * (1L << Math.min(16,Math.max(0,attempt-1)))); }
}
