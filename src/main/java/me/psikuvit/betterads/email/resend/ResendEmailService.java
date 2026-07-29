package me.psikuvit.betterads.email.resend;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.email.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends password-reset codes via the Resend REST API (POST /emails). No
 * dedicated Resend SDK dependency needed — the API is a single plain JSON
 * endpoint, called with the WebClient already on the classpath for outbound
 * AI-provider calls (same pattern as HuggingFaceModerationService).
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String fromAddress;

    public ResendEmailService(@Value("${app.email.resend.api-key:}") String apiKey,
                               @Value("${app.email.from-address}") String fromAddress) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.email.resend.api-key must be configured when app.email.provider=resend");
        }
        this.fromAddress = fromAddress;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        log.info("[resend] ResendEmailService initialized with fromAddress={}", fromAddress);
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        Map<String, Object> body = Map.of(
                "from", fromAddress,
                "to", List.of(toEmail),
                "subject", "Your BetterAds password reset code",
                "html", "<p>Your password reset code is <strong>" + code + "</strong>.</p>"
                        + "<p>It expires in 5 minutes and can only be used once. "
                        + "If you didn't request this, you can safely ignore this email.</p>"
        );
        try {
            webClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            log.info("[resend] Password reset code email sent to {}", toEmail);
        } catch (Exception e) {
            // Swallow: a delivery failure must never surface to the caller (see EmailService).
            log.error("[resend] Failed to send password reset code email: {}", e.getMessage());
        }
    }
}
