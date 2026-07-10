package me.psikuvit.betterads.features;
 
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.SpeechEvaluationService;
import me.psikuvit.betterads.ai.TranslationService;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class FeatureProcessingService {
    // Placeholder orchestration: translation, speech eval, and variant creation

    private final AdVersionRepository adVersionRepository;
    private final TranslationService translationService;
    private final SpeechEvaluationService speechEvaluationService;

    public FeatureProcessingService(AdVersionRepository adVersionRepository, TranslationService translationService, SpeechEvaluationService speechEvaluationService) {
        this.adVersionRepository = adVersionRepository;
        this.translationService = translationService;
        this.speechEvaluationService = speechEvaluationService;
    }

    public void process(String adId, String storageKey, List<String> locales) {
        for (String targetLocale : locales) {
            log.info("Processing features for adId: {}, storageKey: {}, targetLocale: {}", adId, storageKey, targetLocale);

            // 1) Translation (may return new storage key)
            String translatedKey = translationService.translate(storageKey, targetLocale);
            log.debug("Translation completed for adId: {}. Translated key: {}", adId, translatedKey);

            // 2) Speech evaluation
            //double score = speechEvaluationService.evaluate(translatedKey);
            log.info("Speech quality skipped for adId: {}", adId);

            // 3) Persist ad version
            AdVersion v = new AdVersion();
            v.setAdId(Long.valueOf(adId));
            v.setLocale(targetLocale == null ? "" : targetLocale);
            v.setStorageKey(translatedKey);
            v.setFeature(translationService.featureName());
            adVersionRepository.save(v);
            log.info("Ad version persisted for adId: {} with locale: {}, feature: {}", adId, targetLocale, v.getFeature());
        }
    }
}

