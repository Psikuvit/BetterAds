package me.psikuvit.betterads.email;

/**
 * Implementations must never throw and must never let delivery success/failure
 * change caller-visible behavior (timing, response shape, etc.) — the caller
 * only invokes this once it has already decided a message should be sent, and
 * a leaked delivery failure would let an attacker distinguish "account exists
 * but email bounced" from "no account" (user enumeration).
 */
public interface EmailService {
    void sendPasswordResetCode(String toEmail, String code);
}
