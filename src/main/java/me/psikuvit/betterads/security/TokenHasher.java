package me.psikuvit.betterads.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
 */
@Component
public class TokenHasher {
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
}
