package com.yao.traffic.report.infrastructure;

import com.yao.traffic.shared.web.BusinessException;
import com.yao.traffic.shared.web.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ReportRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 120) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int limit;

    public ReportRateLimiter(StringRedisTemplate redis, @Value("${traffic.report.rate-limit-per-minute:20}") int limit) {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid report rate limit");
        this.redis = redis; this.limit = limit;
    }

    public void acquire(long userId) {
        String key = "traffic:report:rate:" + userId + ":" + Instant.now().getEpochSecond() / 60;
        Long count;
        try { count = redis.execute(INCREMENT, List.of(key)); }
        catch (DataAccessException exception) { throw new DependencyUnavailableException("Report rate limiter", exception); }
        if (count == null) throw new DependencyUnavailableException("Report rate limiter", null);
        if (count > limit) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "REPORT_RATE_LIMITED", "提交频率过高，请稍后重试");
    }
}
