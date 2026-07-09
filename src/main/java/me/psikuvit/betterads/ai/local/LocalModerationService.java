package me.psikuvit.betterads.ai.local;

import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalModerationService implements ModerationService {

    private final WebClient webClient;

    public LocalModerationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public ValidationResult moderate(String storageKey) {
        try {
            String res = webClient.post()
                    .uri("/moderate")
                    .bodyValue(Map.of("storageKey", storageKey))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (res == null) return ValidationResult.FLAGGED;
            switch (res.trim().toUpperCase()) {
                case "APPROVED": return ValidationResult.APPROVED;
                case "FLAGGED": return ValidationResult.FLAGGED;
                case "REJECTED": return ValidationResult.REJECTED;
                default: return ValidationResult.FLAGGED;
            }
        } catch (Exception e) {
            // on failure, conservatively flag
            return ValidationResult.FLAGGED;
        }
    }
}
