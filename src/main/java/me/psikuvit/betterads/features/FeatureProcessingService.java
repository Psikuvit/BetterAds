package me.psikuvit.betterads.features;

import me.psikuvit.betterads.ai.SpeechEvaluationService;
import me.psikuvit.betterads.ai.TranslationService;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.stereotype.Service;

@Service
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

    public void process(String adId, String storageKey, String targetLocale) {
        System.out.println("Processing features for ad=" + adId + " key=" + storageKey + " locale=" + targetLocale);

        // 1) Translation (may return new storage key)
        String translatedKey = translationService.translate(storageKey, targetLocale);

        // 2) Speech evaluation
        double score = speechEvaluationService.evaluate(translatedKey);
        System.out.println("Speech quality score=" + score);

        // 3) Persist ad version (one variant for now)
        AdVersion v = new AdVersion();
        v.setAdId(Long.valueOf(adId));
        v.setLocale(targetLocale == null ? "" : targetLocale);
        v.setStorageKey(translatedKey);
        adVersionRepository.save(v);
    }
}

