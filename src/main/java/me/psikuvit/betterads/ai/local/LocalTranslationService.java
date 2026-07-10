package me.psikuvit.betterads.ai.local;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.TranslationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalTranslationService implements TranslationService {

    private final WebClient webClient;

    public LocalTranslationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        log.info("[local] LocalTranslationService initialized with baseUrl={}", baseUrl);
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String translate(String storageKey, String targetLocale) {
        long start = System.currentTimeMillis();
        log.info("[local] POST /translate called with storageKey={}, targetLocale={}", storageKey, targetLocale);
        try {
            String res = webClient.post()
                    .uri("/translate")
                    .bodyValue(Map.of("storageKey", storageKey, "targetLocale", targetLocale))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[local] /translate responded '{}' for storageKey={}, targetLocale={} in {}ms", res, storageKey, targetLocale, elapsedMs);
            return res;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.warn("[local] /translate call failed for storageKey={}, targetLocale={} after {}ms: {}", storageKey, targetLocale, elapsedMs, e.getMessage(), e);
            // fallback to returning original key
            return storageKey;
        }
    }
}
