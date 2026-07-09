package me.psikuvit.betterads.ai.local;

import me.psikuvit.betterads.ai.SpeechEvaluationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalSpeechEvaluationService implements SpeechEvaluationService {

    private final WebClient webClient;

    public LocalSpeechEvaluationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public double evaluate(String storageKey) {
        try {
            Double res = webClient.post()
                    .uri("/speech/evaluate")
                    .bodyValue(Map.of("storageKey", storageKey))
                    .retrieve()
                    .bodyToMono(Double.class)
                    .block();
            return res == null ? 0.0 : res;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
