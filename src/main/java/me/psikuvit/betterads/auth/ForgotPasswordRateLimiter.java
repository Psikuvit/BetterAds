package me.psikuvit.betterads.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Caps how often forgot-password codes can be requested/verified. Independent
 * of the general per-user API rate limit — mirrors fraud/PaymentRateLimiter's
 * Redis INCR+EXPIRE sliding window, keyed by email and/or IP instead of an
 * advertiser id.
 */
@Service
@Slf4j
public class ForgotPasswordRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String REQUEST_EMAIL_PREFIX = "auth:reset:req:email:";
    private static final String REQUEST_IP_PREFIX = "auth:reset:req:ip:";
    private static final String VERIFY_IP_PREFIX = "auth:reset:verify:ip:";

    private final StringRedisTemplate redis;
    private final int maxRequestsPerEmailPerHour;
    private final int maxRequestsPerIpPerHour;
    private final int maxVerifyAttemptsPerIpPerHour;

    public ForgotPasswordRateLimiter(StringRedisTemplate redis,
            @Value("${app.auth.reset-max-requests-per-email-per-hour:5}") int maxRequestsPerEmailPerHour,
            @Value("${app.auth.reset-max-requests-per-ip-per-hour:10}") int maxRequestsPerIpPerHour,
            @Value("${app.auth.reset-max-verify-attempts-per-ip-per-hour:20}") int maxVerifyAttemptsPerIpPerHour) {
        this.redis = redis;
        this.maxRequestsPerEmailPerHour = maxRequestsPerEmailPerHour;
        this.maxRequestsPerIpPerHour = maxRequestsPerIpPerHour;
        this.maxVerifyAttemptsPerIpPerHour = maxVerifyAttemptsPerIpPerHour;
    }

    /** Guards POST /auth/forgot-password — one email/IP can't keep re-triggering sends. */
    public boolean isCodeRequestAllowed(String email, String ip) {
        boolean emailOk = withinLimit(REQUEST_EMAIL_PREFIX + email.toLowerCase(), maxRequestsPerEmailPerHour);
        boolean ipOk = withinLimit(REQUEST_IP_PREFIX + ip, maxRequestsPerIpPerHour);
        if (!emailOk || !ipOk) {
            log.warn("Password reset code request throttled for email={}, ip={}", email, ip);
            return false;
        }
        return true;
    }

    /** Guards POST /auth/verify-reset-code — throttles by IP since a code is single-guess anyway. */
    public boolean isVerifyAllowed(String ip) {
        boolean ok = withinLimit(VERIFY_IP_PREFIX + ip, maxVerifyAttemptsPerIpPerHour);
        if (!ok) {
            log.warn("Password reset code verification throttled for ip={}", ip);
        }
        return ok;
    }

    private boolean withinLimit(String key, int max) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        long total = count != null ? count : 0;
        return total <= max;
    }
}
