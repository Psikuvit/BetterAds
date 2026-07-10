package me.psikuvit.betterads.ai.mock;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.TranslationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockTranslationService implements TranslationService {

    @Override
    public String translate(String storageKey, String targetLocale) {
        log.info("[mock] translate() called with storageKey={}, targetLocale={}", storageKey, targetLocale);
        // Simulate translation by appending locale tag to key
        if (storageKey == null) {
            log.warn("[mock] translate() received null storageKey");
            return null;
        }
        String result = storageKey;// + "::translated::" + targetLocale;
        log.info("[mock] translate() result={} for storageKey={}, targetLocale={}", result, storageKey, targetLocale);
        return result;
    }
}
