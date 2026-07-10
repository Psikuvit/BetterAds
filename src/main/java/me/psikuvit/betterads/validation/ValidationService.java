package me.psikuvit.betterads.validation;

import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.dto.HumanReviewQueue;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.stereotype.Service;

@Service
public class ValidationService {

    private final ModerationService moderationService;
    private final HumanReviewQueue humanReviewQueue;

    public ValidationService(ModerationService moderationService, HumanReviewQueue humanReviewQueue) {
        this.moderationService = moderationService;
        this.humanReviewQueue = humanReviewQueue;
    }

    /**
     * Validate an ad by its storage key (or ad id for now). Returns the moderation result.
     */
    public ValidationResult validate(String storageKey, String adId) {
        ValidationResult res = moderationService.moderate(storageKey);
        switch (res) {
            case APPROVED:
                // proceed to feature processing (handled by worker) - nothing to do here
                break;
            case FLAGGED:
                // enqueue for human review
                humanReviewQueue.enqueue(adId);
                break;
            case REJECTED:
                // mark as rejected (worker/DB should handle persisting status)
                break;
        }
        return res;
    }
}

