package me.psikuvit.betterads.worker;

import me.psikuvit.betterads.features.FeatureProcessingService;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.repo.AdRepository;
import me.psikuvit.betterads.validation.ValidationResult;
import me.psikuvit.betterads.validation.ValidationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkerConsumer {

    private final ValidationService validationService;
    private final AdRepository adRepository;
    private final FeatureProcessingService featureProcessingService;

    public WorkerConsumer(ValidationService validationService, AdRepository adRepository, FeatureProcessingService featureProcessingService) {
        this.validationService = validationService;
        this.adRepository = adRepository;
        this.featureProcessingService = featureProcessingService;
    }

    @RabbitListener(queues = "ad-processing")
    @Transactional
    public void handle(String adIdStr) {
        System.out.println("Received job for adId=" + adIdStr);
        long adId;
        try {
            adId = Long.parseLong(adIdStr);
        } catch (NumberFormatException e) {
            System.err.println("Invalid adId from queue: " + adIdStr);
            return;
        }

        adRepository.findById(adId).ifPresent(ad -> {
            try {
                ad.setStatus("validating");
                adRepository.save(ad);

                ValidationResult result = validationService.validate(ad.getStorageKey(), ad.getId().toString());

                if (result == ValidationResult.APPROVED) {
                    ad.setStatus("processing");
                    adRepository.save(ad);

                    featureProcessingService.process(ad.getId().toString(), ad.getStorageKey(), ad.getTargetLocale());

                    ad.setStatus("live");
                    adRepository.save(ad);
                } else if (result == ValidationResult.FLAGGED) {
                    ad.setStatus("flagged");
                    adRepository.save(ad);
                } else {
                    ad.setStatus("rejected");
                    adRepository.save(ad);
                }
            } catch (Exception ex) {
                // mark as failed for retry/inspection
                ad.setStatus("failed");
                adRepository.save(ad);
                System.err.println("Worker failed for adId=" + adId + " : " + ex.getMessage());
            }
        });
    }
}

