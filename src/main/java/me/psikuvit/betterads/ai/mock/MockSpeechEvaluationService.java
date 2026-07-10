package me.psikuvit.betterads.ai.mock;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.SpeechEvaluationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockSpeechEvaluationService implements SpeechEvaluationService {

    @Override
    public double evaluate(String storageKey) {
        log.info("[mock] evaluate() called with storageKey={}", storageKey);
        // Return a deterministic pseudo-random score based on key
        if (storageKey == null) {
            log.warn("[mock] evaluate() received null storageKey, returning default score");
            return 0.5;
        }
        int h = Math.abs(storageKey.hashCode());
        double score = 0.6 + (h % 40) / 100.0; // 0.6..0.99
        log.info("[mock] evaluate() result={} for storageKey={}", score, storageKey);
        return score;
    }
}
