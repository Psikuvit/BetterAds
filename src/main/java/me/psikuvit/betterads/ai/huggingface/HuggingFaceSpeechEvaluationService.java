package me.psikuvit.betterads.ai.huggingface;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.SpeechEvaluationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "huggingface")
public class HuggingFaceSpeechEvaluationService implements SpeechEvaluationService {

    @Override
    public double evaluate(String storageKey) {
        if (storageKey == null) {
            log.info("[huggingface-speech] null key, returning 0.75");
            return 0.75;
        }
        int h = Math.abs(storageKey.hashCode());
        double score = 0.6 + (h % 40) / 100.0;
        log.info("[huggingface-speech] fake score={} for key={}", score, storageKey);
        return score;
    }
}
