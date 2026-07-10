package me.psikuvit.betterads.ai.local;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.SpeechEvaluationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalSpeechEvaluationService implements SpeechEvaluationService {

    private final WebClient webClient;

    public LocalSpeechEvaluationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        log.info("[local] LocalSpeechEvaluationService initialized with baseUrl={}", baseUrl);
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public double evaluate(String storageKey) {
        long start = System.currentTimeMillis();
        log.info("[local] POST /speech/evaluate called with storageKey={}", storageKey);
        try {
            Double res = webClient.post()
                    .uri("/speech/evaluate")
                    .bodyValue(Map.of("storageKey", storageKey))
                    .retrieve()
                    .bodyToMono(Double.class)
                    .block();
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[local] /speech/evaluate responded {} for storageKey={} in {}ms", res, storageKey, elapsedMs);
            return res == null ? 0.0 : res;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.warn("[local] /speech/evaluate call failed for storageKey={} after {}ms: {}", storageKey, elapsedMs, e.getMessage(), e);
            return 0.0;
        }
    }
}
