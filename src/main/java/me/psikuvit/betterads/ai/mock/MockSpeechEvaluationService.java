package me.psikuvit.betterads.ai.mock;

import me.psikuvit.betterads.ai.SpeechEvaluationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockSpeechEvaluationService implements SpeechEvaluationService {

    @Override
    public double evaluate(String storageKey) {
        // Return a deterministic pseudo-random score based on key
        if (storageKey == null) return 0.5;
        int h = Math.abs(storageKey.hashCode());
        return 0.6 + (h % 40) / 100.0; // 0.6..0.99
    }
}
