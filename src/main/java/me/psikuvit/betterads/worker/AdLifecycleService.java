package me.psikuvit.betterads.worker;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.features.FeatureProcessingService;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import org.springframework.stereotype.Service;

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
     * Shared by the worker's happy-path (validate -> APPROVED) and the admin
     * manual-review "approve" decision, so both go through feature processing
     * and embed-link generation the same way.
     */
    public void moveToLive(Ad ad) {
        ad.setStatus("processing");
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to processing", ad.getId());

        featureProcessingService.process(ad.getId().toString(), ad.getStorageKey(), ad.getTargetLocale());
        log.info("Feature processing completed for Ad ID: {}", ad.getId());

        ad.setStatus("live");
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to live", ad.getId());

        var link = embedService.generateLink(ad.getId());
        log.info("Ad ID: {} is live — embed token={}", ad.getId(), link.getToken());
    }

    public void reject(Ad ad) {
        ad.setStatus("rejected");
        adRepository.save(ad);
        eventPublisher.publish(ad.getId(), ad.getStatus());
        log.info("Ad ID: {} status updated to rejected", ad.getId());
    }
}
