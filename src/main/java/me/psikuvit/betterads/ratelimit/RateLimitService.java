package me.psikuvit.betterads.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import io.github.bucket4j.Bandwidth;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitService {
    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();
    
    private final int requestsPerMinute;

    public RateLimitService(RateLimitProperties properties) {
        this.requestsPerMinute = properties.getRequestsPerMinute();
    }

    public boolean isAllowed(String key) {
        return resolveBucket(key).tryConsume(1);
    }

    private Bucket resolveBucket(String key) {
        return cache.computeIfAbsent(key, k -> createNewBucket());
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.classic(requestsPerMinute, Refill.intervally(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket4j.builder().addLimit(limit).build();
    }

    public long getRemainingTokens(String key) {
        return resolveBucket(key).getAvailableTokens();
    }
}
