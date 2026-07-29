package me.psikuvit.betterads.email.mock;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.email.EmailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default provider — logs the code instead of sending a real email, so local
 * dev/test never depends on (or accidentally burns) a real Resend quota.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.email.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmailService implements EmailService {

    @Override
    public void sendPasswordResetCode(String toEmail, String code) {
        log.info("[mock-email] Password reset code for {}: {} (would be sent via Resend in a real environment)", toEmail, code);
    }
}
