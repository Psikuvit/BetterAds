package me.psikuvit.betterads.validation;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ValidationService {

    private final ModerationService moderationService;

    public ValidationService(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    /**
     * Validate an ad by its storage key (or ad id for now). Returns the moderation result.
     */
    public ValidationResult validate(String storageKey, String adId) {
        log.debug("Validating adId: {} with storageKey: {}", adId, storageKey);
        ValidationResult res = moderationService.moderate(storageKey);
        log.debug("Moderation result for adId: {}: {}", adId, res);
        switch (res) {
            case APPROVED:
                // proceed to feature processing (handled by worker) - nothing to do here
                break;
            case FLAGGED:
                log.warn("Ad ID: {} flagged for human review", adId);
                // worker persists status="flagged" on the Ad row; that's the source of truth
                break;
            case REJECTED:
                log.warn("Ad ID: {} rejected by moderation", adId);
                // mark as rejected (worker/DB should handle persisting status)
                break;
        }
        return res;
    }
}
