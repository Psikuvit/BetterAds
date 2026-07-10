package me.psikuvit.betterads.fraud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Short-lived, one-time-use signed token issued when the embed widget is
 * rendered. A view-recording call presenting a valid token proves it came
 * from a genuine widget load and can't be replayed, so it skips the
 * IP-rate-limit fallback check in FraudService.
 */
@Service
public class ViewTokenService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(2);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String USED_KEY_PREFIX = "view-token-used:";

    private final StringRedisTemplate redis;
    private final SecretKeySpec key;

    public ViewTokenService(StringRedisTemplate redis,
                            @Value("${app.fraud.view-token-secret:${app.auth.jwt-secret}}") String secret) {
        this.redis = redis;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String issueToken(Long adId) {
        long expiry = Instant.now().plus(TOKEN_TTL).toEpochMilli();
        String nonce = UUID.randomUUID().toString();
        String payload = adId + "." + expiry + "." + nonce;
        return payload + "." + sign(payload);
    }

    public boolean validateAndConsume(String token, Long adId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.", 4);
        if (parts.length != 4) {
            return false;
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        String expectedSignature = sign(payload);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }

        try {
            Long tokenAdId = Long.parseLong(parts[0]);
            long expiry = Long.parseLong(parts[1]);
            if (!tokenAdId.equals(adId) || Instant.now().toEpochMilli() > expiry) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String nonce = parts[2];
        Boolean firstUse = redis.opsForValue().setIfAbsent(USED_KEY_PREFIX + nonce, "1", TOKEN_TTL);
        return Boolean.TRUE.equals(firstUse);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign view token", e);
        }
    }
}
