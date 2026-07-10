package me.psikuvit.betterads.worker;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.features.FeatureProcessingService;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import me.psikuvit.betterads.validation.ValidationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class WorkerConsumer {

    private final ValidationService validationService;
    private final AdRepository adRepository;
    private final FeatureProcessingService featureProcessingService;
    private final EmbedService embedService;

    public WorkerConsumer(ValidationService validationService, AdRepository adRepository,
                          FeatureProcessingService featureProcessingService, EmbedService embedService) {
        this.validationService = validationService;
        this.adRepository = adRepository;
        this.featureProcessingService = featureProcessingService;
        this.embedService = embedService;
    }

    @RabbitListener(queues = "ad-processing")
    @Transactional
    public void handle(String adIdStr) {
        log.info("Received job for adId={}", adIdStr);
        long adId;
        try {
            adId = Long.parseLong(adIdStr);
        } catch (NumberFormatException e) {
            log.error("Invalid adId from queue: {}", adIdStr);
            return;
        }

        adRepository.findById(adId).ifPresent(ad -> {
            try {
                ad.setStatus("validating");
                adRepository.save(ad);
                log.info("Ad ID: {} status updated to validating", adId);

                ValidationResult result = validationService.validate(ad.getStorageKey(), ad.getId().toString());
                log.info("Validation result for Ad ID: {}: {}", adId, result);

                if (result == ValidationResult.APPROVED) {
                    ad.setStatus("processing");
                    adRepository.save(ad);
                    log.info("Ad ID: {} status updated to processing", adId);

                    featureProcessingService.process(ad.getId().toString(), ad.getStorageKey(), ad.getTargetLocale());
                    log.info("Feature processing completed for Ad ID: {}", adId);

                    ad.setStatus("live");
                    adRepository.save(ad);
                    log.info("Ad ID: {} status updated to live", adId);

                    // Generate embed link now that the ad is live
                    var link = embedService.generateLink(adId);
                    log.info("Ad ID: {} is live — embed token={}", adId, link.getToken());

                } else if (result == ValidationResult.FLAGGED) {
                    ad.setStatus("flagged");
                    adRepository.save(ad);
                    log.info("Ad ID: {} status updated to flagged (pending human review)", adId);
                } else {
                    ad.setStatus("rejected");
                    adRepository.save(ad);
                    log.info("Ad ID: {} status updated to rejected", adId);
                }
            } catch (Exception ex) {
                ad.setStatus("failed");
                adRepository.save(ad);
                log.error("Worker failed for adId={} : {}", adId, ex.getMessage(), ex);
            }
        });
    }
}
