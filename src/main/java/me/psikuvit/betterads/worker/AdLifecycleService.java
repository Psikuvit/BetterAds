package me.psikuvit.betterads.worker;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.features.FeatureProcessingService;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AdLifecycleService {

    private final AdRepository adRepository;
    private final FeatureProcessingService featureProcessingService;
    private final EmbedService embedService;
    private final AdStatusEventPublisher eventPublisher;

    public AdLifecycleService(AdRepository adRepository,
                              FeatureProcessingService featureProcessingService,
                              EmbedService embedService,
                              AdStatusEventPublisher eventPublisher) {
        this.adRepository = adRepository;
        this.featureProcessingService = featureProcessingService;
        this.embedService = embedService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Called once the advertiser has chosen which locales to generate,
     * after an ad reaches AWAITING_FEATURES (whether via the worker's
     * happy-path validation or an admin's manual-review "approve" decision).
     */
    public void moveToLive(Ad ad, List<String> locales) {
        ad.setStatus(AdStatus.PROCESSING);
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to processing", ad.getId());

        try {
            featureProcessingService.process(ad.getId().toString(), ad.getStorageKey(), locales);
        } catch (RuntimeException e) {
            ad.setStatus(AdStatus.AWAITING_FEATURES);
            adRepository.save(ad);
            eventPublisher.publish(ad.getId(), ad.getStatus());
            log.warn("Feature processing failed for Ad ID: {}, reverted to awaiting_features: {}", ad.getId(), e.getMessage());
            throw e;
        }
        log.info("Feature processing completed for Ad ID: {}", ad.getId());

        ad.setStatus(AdStatus.LIVE);
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to live", ad.getId());

        var link = embedService.generateLink(ad.getId());
        log.info("Ad ID: {} is live — embed token={}", ad.getId(), link.getToken());
    }

    public void reject(Ad ad) {
        ad.setStatus(AdStatus.REJECTED);
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to rejected", ad.getId());
    }
}
