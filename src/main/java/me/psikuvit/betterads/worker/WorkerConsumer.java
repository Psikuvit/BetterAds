package me.psikuvit.betterads.worker;

import lombok.extern.slf4j.Slf4j;
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
    private final AdLifecycleService adLifecycleService;
    private final AdStatusEventPublisher eventPublisher;

    public WorkerConsumer(ValidationService validationService, AdRepository adRepository,
                          AdLifecycleService adLifecycleService, AdStatusEventPublisher eventPublisher) {
        this.validationService = validationService;
        this.adRepository = adRepository;
        this.adLifecycleService = adLifecycleService;
        this.eventPublisher = eventPublisher;
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
                eventPublisher.publish(adId, ad.getStatus());
                log.info("Ad ID: {} status updated to validating", adId);

                ValidationResult result = validationService.validate(ad.getStorageKey(), ad.getId().toString());
                log.info("Validation result for Ad ID: {}: {}", adId, result);

                if (result == ValidationResult.APPROVED) {
                    adLifecycleService.moveToLive(ad);
                } else if (result == ValidationResult.FLAGGED) {
                    ad.setStatus("flagged");
                    adRepository.save(ad);
                    eventPublisher.publish(adId, ad.getStatus());
                    log.info("Ad ID: {} status updated to flagged (pending human review)", adId);
                } else {
                    adLifecycleService.reject(ad);
                }
            } catch (Exception ex) {
                ad.setStatus("failed");
                adRepository.save(ad);
                eventPublisher.publish(adId, ad.getStatus());
                log.error("Worker failed for adId={} : {}", adId, ex.getMessage(), ex);
            }
        });
    }
}
