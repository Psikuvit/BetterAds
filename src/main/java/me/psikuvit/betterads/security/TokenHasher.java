package me.psikuvit.betterads.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque tokens (refresh / password-reset) are high-entropy random strings, not
 * human passwords, so they're hashed with plain SHA-256 rather than bcrypt —
 * bcrypt's per-hash salt makes exact-match DB lookup impossible, and its slow
 * KDF is unnecessary here since the token itself can't be brute-forced.
 *
 * Numeric codes (forgot-password email codes) are the opposite case: only
 * 1,000,000 possible values, so a plain hash would let anyone who dumps the DB
 * precompute every hash offline. hmac() keys the hash with a secret pepper
 * that never leaves app config, so a DB-only leak can't be brute-forced.
 */
@Component
public class TokenHasher {
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Zero-padded 6-digit code, e.g. "004213". */
    public String generateNumericCode() {
        int value = secureRandom.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
