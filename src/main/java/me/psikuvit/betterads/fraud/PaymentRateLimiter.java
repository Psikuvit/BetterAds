package me.psikuvit.betterads.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Caps how often one advertiser can attempt to fund a campaign. Aimed at
 * card-testing abuse, which relies on rapid repeated attempts against a
 * payment endpoint — independent of the general per-user API rate limit.
 */
@Service
@Slf4j
public class PaymentRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String KEY_PREFIX = "fraud:fund:";

    private final StringRedisTemplate redis;
    private final int maxAttemptsPerHour;

    public PaymentRateLimiter(StringRedisTemplate redis,
                              @Value("${app.fraud.max-funding-attempts-per-hour:5}") int maxAttemptsPerHour) {
        this.redis = redis;
        this.maxAttemptsPerHour = maxAttemptsPerHour;
    }

    public boolean isFundingAllowed(Long advertiserId) {
        String key = KEY_PREFIX + advertiserId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        long total = count != null ? count : 0;
        if (total > maxAttemptsPerHour) {
            log.warn("Advertiser {} exceeded funding attempt limit: {} attempts in the last hour", advertiserId, total);
            return false;
        }
        return true;
    }
}
