package me.psikuvit.betterads.ai.mock;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockModerationService implements ModerationService {

    @Override
    public ValidationResult moderate(String storageKey) {
        log.info("[mock] moderate() called with storageKey={}", storageKey);
        if (storageKey == null) {
            log.warn("[mock] moderate() received null storageKey, flagging");
            return ValidationResult.FLAGGED;
        }
        String k = storageKey.toLowerCase();
        ValidationResult result;
        if (k.contains("reject")) result = ValidationResult.REJECTED;
        else if (k.contains("flag") || k.contains("review")) result = ValidationResult.FLAGGED;
        else result = ValidationResult.APPROVED;
        log.info("[mock] moderate() result={} for storageKey={}", result, storageKey);
        return result;
    }
}
