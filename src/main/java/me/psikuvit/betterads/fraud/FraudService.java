package me.psikuvit.betterads.fraud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Duration CAMPAIGN_WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final int maxCampaignViewsPerMinute;

    public FraudService(StringRedisTemplate redis,
                        @Value("${app.fraud.max-campaign-views-per-minute:200}") int maxCampaignViewsPerMinute) {
        this.redis = redis;
        this.maxCampaignViewsPerMinute = maxCampaignViewsPerMinute;
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

    /**
     * A campaign-wide velocity cap independent of source IP. Catches the
     * fraud pattern a per-IP window structurally can't: a botnet/proxy
     * rotation attack spread across many distinct IPs, each individually
     * under the per-IP cap, all inflating one campaign's view count/spend.
     */
    public boolean isCampaignOverVelocity(Long campaignId) {
        if (campaignId == null) {
            return false;
        }
        String key = "fraud:campaign:" + campaignId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, CAMPAIGN_WINDOW);
        }
        long total = count != null ? count : 0;
        if (total > maxCampaignViewsPerMinute) {
            log.warn("Fraud detected: campaign {} received {} views in last 60s (cap={})", campaignId, total, maxCampaignViewsPerMinute);
            return true;
        }
        return false;
    }
}
