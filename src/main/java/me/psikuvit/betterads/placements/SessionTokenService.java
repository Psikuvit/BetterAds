package me.psikuvit.betterads.placements;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Signs and verifies session tokens for the placements API. Unlike
 * ViewTokenService's per-ad token, single-use enforcement for these tokens
 * doesn't rely on a Redis nonce — the token is looked up by exact match
 * against the durable ad_sessions.session_token column, and per-event-type
 * single-use is enforced by session_events' DB unique constraint. This uses
 * its own dedicated secret rather than falling back to the JWT secret, so
 * rotating one doesn't silently affect the other.
 */
@Service
public class SessionTokenService {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SessionTokenService(@Value("${app.fraud.session-token-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.fraud.session-token-secret must be configured — refusing to start without one");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.fraud.session-token-secret must be at least " + MIN_SECRET_LENGTH + " characters (HS256 minimum key size)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String issue(Instant expiresAt) {
        String nonce = UUID.randomUUID().toString();
        String payload = nonce + "." + expiresAt.toEpochMilli();
        return payload + "." + sign(payload);
    }

    /**
     * Verifies signature and expiry only — it does not know whether the
     * token has actually been consumed/exists in the DB. Callers must still
     * look the token up via AdSessionRepository.findBySessionToken.
     */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3) {
            return false;
        }
        String payload = parts[0] + "." + parts[1];
        String expectedSignature = sign(payload);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            long expiry = Long.parseLong(parts[1]);
            return Instant.now().toEpochMilli() <= expiry;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign session token", e);
        }
    }
}
