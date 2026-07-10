package me.psikuvit.betterads.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class FraudService {

    // Max views from a single IP per minute before flagging as fraud
    private static final int MAX_VIEWS_PER_MINUTE = 30;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public FraudService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLikelyFraud(String ip) {
        String key = "fraud:ip:" + ip;
        long now = Instant.now().toEpochMilli();
        long cutoff = now - WINDOW.toMillis();

        // Redis-backed sliding window: distributed across instances, survives restarts.
        redis.opsForZSet().removeRangeByScore(key, 0, cutoff);
        redis.opsForZSet().add(key, UUID.randomUUID().toString(), now);
        redis.expire(key, WINDOW);

        Long count = redis.opsForZSet().zCard(key);
        long total = count != null ? count : 0;
        if (total > MAX_VIEWS_PER_MINUTE) {
            log.warn("Fraud detected: IP {} made {} impressions in last 60s", ip, total);
            return true;
        }
        return false;
    }
}
