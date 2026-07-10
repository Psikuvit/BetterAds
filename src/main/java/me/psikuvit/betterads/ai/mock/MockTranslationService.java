package me.psikuvit.betterads.ai.mock;

import me.psikuvit.betterads.ai.TranslationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockTranslationService implements TranslationService {

    @Override
    public String translate(String storageKey, String targetLocale) {
        // Simulate translation by appending locale tag to key
        if (storageKey == null) return null;
        return storageKey;// + "::translated::" + targetLocale;
    }
}
