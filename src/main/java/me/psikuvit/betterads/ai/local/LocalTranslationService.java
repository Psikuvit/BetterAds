package me.psikuvit.betterads.ai.local;

import me.psikuvit.betterads.ai.TranslationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "local")
public class LocalTranslationService implements TranslationService {

    private final WebClient webClient;

    public LocalTranslationService(@Value("${app.ai.local-base-url:http://localhost:9000}") String baseUrl, WebClient.Builder builder) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String translate(String storageKey, String targetLocale) {
        try {
            String res = webClient.post()
                    .uri("/translate")
                    .bodyValue(Map.of("storageKey", storageKey, "targetLocale", targetLocale))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return res;
        } catch (Exception e) {
            // fallback to returning original key
            return storageKey;
        }
    }
}
