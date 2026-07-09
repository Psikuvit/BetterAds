package me.psikuvit.betterads.ai;

import me.psikuvit.betterads.validation.ValidationResult;

public interface ModerationService {
    ValidationResult moderate(String storageKey);
}
