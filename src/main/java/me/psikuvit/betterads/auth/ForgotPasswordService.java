package me.psikuvit.betterads.auth;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.exceptions.AuthenticationException;
import me.psikuvit.betterads.email.EmailService;
import me.psikuvit.betterads.fraud.exceptions.TooManyRequestsException;
import me.psikuvit.betterads.security.TokenHasher;
import me.psikuvit.betterads.storage.entities.PasswordResetCode;
import me.psikuvit.betterads.storage.entities.PasswordResetToken;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.PasswordResetCodeRepository;
import me.psikuvit.betterads.storage.repositories.PasswordResetTokenRepository;
import me.psikuvit.betterads.storage.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Three-step forgot-password flow: request a code (emailed), verify the code
 * to obtain a short-lived reset session token, then spend that token to set a
 * new password. Splitting verify from reset means a guessed/leaked code alone
 * can't be replayed to set a password without also holding the session token.
 *
 * Security properties, all deliberate:
 *  - Only one active code exists per user at a time — requesting a new one
 *    invalidates whatever was outstanding ("the code can be obtained once").
 *  - Codes expire after app.auth.reset-code-expiration-ms (default 5 min).
 *  - A single wrong guess invalidates the code immediately — there is no
 *    multi-attempt budget, so a 6-digit code can never be brute-forced online.
 *  - Code hashes are HMAC'd with a dedicated pepper (TokenHasher.hmac), not
 *    plain SHA-256 — a 6-digit code's ~1e6 keyspace is trivially rainbow-table-able
 *    with an unkeyed hash if the DB ever leaks.
 *  - Every outward-facing outcome (unknown email, wrong code, expired code,
 *    used code) returns the same generic message, to avoid user enumeration.
 */
@Service
@Slf4j
public class ForgotPasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetCodeRepository passwordResetCodeRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TokenHasher tokenHasher;
    private final EmailService emailService;
    private final ForgotPasswordRateLimiter rateLimiter;
    private final String resetCodeSecret;
    private final long codeExpirationMs;
    private final long sessionExpirationMs;

    public ForgotPasswordService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  PasswordResetCodeRepository passwordResetCodeRepository,
                                  PasswordResetTokenRepository passwordResetTokenRepository,
                                  TokenHasher tokenHasher,
                                  EmailService emailService,
                                  ForgotPasswordRateLimiter rateLimiter,
                                  @Value("${app.auth.reset-code-secret:}") String resetCodeSecret,
                                  @Value("${app.auth.reset-code-expiration-ms:300000}") long codeExpirationMs,
                                  @Value("${app.auth.reset-expiration-ms:600000}") long sessionExpirationMs) {
        if (resetCodeSecret == null || resetCodeSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.reset-code-secret must be configured — refusing to start without one");
        }
        if (resetCodeSecret.length() < 32) {
            throw new IllegalStateException(
                    "app.auth.reset-code-secret must be at least 32 characters (HMAC-SHA256 minimum key size)");
        }
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetCodeRepository = passwordResetCodeRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.tokenHasher = tokenHasher;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.resetCodeSecret = resetCodeSecret;
        this.codeExpirationMs = codeExpirationMs;
        this.sessionExpirationMs = sessionExpirationMs;
    }

    @Transactional
    public void requestCode(String email, String clientIp) {
        if (!rateLimiter.isCodeRequestAllowed(email, clientIp)) {
            throw new TooManyRequestsException("Too many password reset requests. Try again later.");
        }

        // Always behave the same regardless of whether the email exists, to avoid user enumeration.
        userRepository.findByEmail(email).ifPresent(user -> {
            supersedeActiveCodes(user.getId());

            String rawCode = tokenHasher.generateNumericCode();
            PasswordResetCode resetCode = new PasswordResetCode();
            resetCode.setUserId(user.getId());
            resetCode.setCodeHash(hashCode(user.getId(), rawCode));
            resetCode.setExpiresAt(Instant.now().plusMillis(codeExpirationMs));
            passwordResetCodeRepository.save(resetCode);

            emailService.sendPasswordResetCode(user.getEmail(), rawCode);
            log.info("Password reset code issued for userId={}", user.getId());
        });
    }

    @Transactional
    public String verifyCode(String email, String rawCode, String clientIp) {
        if (!rateLimiter.isVerifyAllowed(clientIp)) {
            throw new TooManyRequestsException("Too many attempts. Try again later.");
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Same failure shape as a wrong code — don't leak whether the email exists.
            throw new AuthenticationException("Invalid or expired code");
        }
        User user = userOpt.get();

        String codeHash = hashCode(user.getId(), rawCode);
        PasswordResetCode resetCode = passwordResetCodeRepository.findByCodeHash(codeHash)
                .filter(c -> c.getUserId().equals(user.getId()))
                .filter(c -> c.getUsedAt() == null && c.getInvalidatedAt() == null)
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .orElse(null);

        if (resetCode == null) {
            // Wrong, expired, or already-used code: kill whatever is still active for this
            // user outright, so the same forgot-password session can never be retried —
            // eliminates online brute-forcing of the 6-digit code entirely.
            invalidateOnFailedAttempt(user.getId());
            log.warn("Password reset code verification failed for userId={}", user.getId());
            throw new AuthenticationException("Invalid or expired code");
        }

        resetCode.setUsedAt(Instant.now());
        resetCode.setAttempts(resetCode.getAttempts() + 1);
        passwordResetCodeRepository.save(resetCode);

        String rawSessionToken = tokenHasher.generateOpaqueToken();
        PasswordResetToken sessionToken = new PasswordResetToken();
        sessionToken.setUserId(user.getId());
        sessionToken.setTokenHash(tokenHasher.hash(rawSessionToken));
        sessionToken.setExpiresAt(Instant.now().plusMillis(sessionExpirationMs));
        passwordResetTokenRepository.save(sessionToken);

        log.info("Password reset code verified for userId={}", user.getId());
        return rawSessionToken;
    }

    @Transactional
    public void resetPassword(String rawSessionToken, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawSessionToken))
                .filter(rt -> rt.getUsedAt() == null && rt.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AuthenticationException("Invalid or expired reset session"));

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new AuthenticationException("User no longer exists"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);
        log.info("Password reset completed for userId={}", user.getId());
    }

    private String hashCode(Long userId, String rawCode) {
        // Bind the hash to the user so two different users who happen to draw the same
        // 6-digit code never collide in the code_hash lookup.
        return tokenHasher.hmac(userId + ":" + rawCode, resetCodeSecret);
    }

    /** Called when issuing a fresh code — only one code may be active per user at a time. */
    private void supersedeActiveCodes(Long userId) {
        List<PasswordResetCode> active = passwordResetCodeRepository
                .findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(code -> code.setInvalidatedAt(now));
        passwordResetCodeRepository.saveAll(active);
    }

    /** Called on a wrong/mismatched guess — ends the session and records the attempt. */
    private void invalidateOnFailedAttempt(Long userId) {
        List<PasswordResetCode> active = passwordResetCodeRepository
                .findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(code -> {
            code.setInvalidatedAt(now);
            code.setAttempts(code.getAttempts() + 1);
        });
        passwordResetCodeRepository.saveAll(active);
    }
}
