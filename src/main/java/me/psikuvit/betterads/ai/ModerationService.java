package me.psikuvit.betterads.ai;

import me.psikuvit.betterads.validation.dto.ValidationResult;

public interface ModerationService {
    ValidationResult moderate(String storageKey);
}
