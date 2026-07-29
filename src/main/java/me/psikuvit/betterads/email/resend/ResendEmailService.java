package me.psikuvit.betterads.email.resend;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.email.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Sends password-reset codes via the official Resend Java SDK
 * (https://github.com/resend/resend-java), rather than calling the REST API
 * directly.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    private final Resend resend;
    private final String fromAddress;

    public ResendEmailService(@Value("${app.email.resend.api-key:}") String apiKey,
                               @Value("${app.email.from-address}") String fromAddress) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.email.resend.api-key must be configured when app.email.provider=resend");
        }
        this.fromAddress = fromAddress;
        this.resend = new Resend(apiKey);
        log.info("[resend] ResendEmailService initialized with fromAddress={}", fromAddress);
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(fromAddress)
                .to(toEmail)
                .subject("Your BetterAds password reset code")
                .html("<p>Your password reset code is <strong>" + code + "</strong>.</p>"
                        + "<p>It expires in 5 minutes and can only be used once. "
                        + "If you didn't request this, you can safely ignore this email.</p>")
                .build();
        try {
            resend.emails().send(params);
            log.info("[resend] Password reset code email sent to {}", toEmail);
        } catch (ResendException e) {
            // Swallow: a delivery failure must never surface to the caller (see EmailService).
            log.error("[resend] Failed to send password reset code email: {}", e.getMessage());
        }
    }
}
