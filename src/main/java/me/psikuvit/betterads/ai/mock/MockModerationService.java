package me.psikuvit.betterads.ai.mock;

import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockModerationService implements ModerationService {

    @Override
    public ValidationResult moderate(String storageKey) {
        if (storageKey == null) return ValidationResult.FLAGGED;
        String k = storageKey.toLowerCase();
        if (k.contains("reject")) return ValidationResult.REJECTED;
        if (k.contains("flag") || k.contains("review")) return ValidationResult.FLAGGED;
        return ValidationResult.APPROVED;
    }
}
