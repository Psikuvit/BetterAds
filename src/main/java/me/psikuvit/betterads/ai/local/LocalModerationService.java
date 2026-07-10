package me.psikuvit.betterads.ai.local;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalModerationService implements ModerationService {

    private final WebClient webClient;

    public LocalModerationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        log.info("[local] LocalModerationService initialized with baseUrl={}", baseUrl);
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public ValidationResult moderate(String storageKey) {
        long start = System.currentTimeMillis();
        log.info("[local] POST /moderate called with storageKey={}", storageKey);
        try {
            String res = webClient.post()
                    .uri("/moderate")
                    .bodyValue(Map.of("storageKey", storageKey))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[local] /moderate responded '{}' for storageKey={} in {}ms", res, storageKey, elapsedMs);
            if (res == null) {
                log.warn("[local] /moderate returned null body for storageKey={}, flagging", storageKey);
                return ValidationResult.FLAGGED;
            }
            return switch (res.trim().toUpperCase()) {
                case "APPROVED" -> ValidationResult.APPROVED;
                case "FLAGGED" -> ValidationResult.FLAGGED;
                case "REJECTED" -> ValidationResult.REJECTED;
                default -> {
                    log.warn("[local] /moderate returned unrecognized value '{}' for storageKey={}, flagging", res, storageKey);
                    yield ValidationResult.FLAGGED;
                }
            };
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.warn("[local] /moderate call failed for storageKey={} after {}ms: {}", storageKey, elapsedMs, e.getMessage(), e);
            // on failure, conservatively flag
            return ValidationResult.FLAGGED;
        }
    }
}
