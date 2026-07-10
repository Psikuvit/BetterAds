package me.psikuvit.betterads.api;
 
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.queue.ProcessingQueueService;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
public class UploadController {

    private final ProcessingQueueService processingQueueService;
    private final AdRepository adRepository;

    public UploadController(ProcessingQueueService processingQueueService, AdRepository adRepository) {
        this.processingQueueService = processingQueueService;
        this.adRepository = adRepository;
    }

    @PostMapping("/upload/confirm")
    @PreAuthorize("hasRole('ADVERTISER')")
    public Map<String, Object> confirmUpload(@RequestBody Map<String, Object> payload) {
        log.info("Received upload confirmation request: {}", payload);
        // Validate and persist ad record
        Long campaignId = payload.get("campaignId") != null ? Long.valueOf(payload.get("campaignId").toString()) : null;
        String title = (String) payload.getOrDefault("title", "");
        String storageKey = (String) payload.getOrDefault("storageKey", "");
        String targetLocale = (String) payload.getOrDefault("targetLocale", "en");

        Ad ad = new Ad();
        ad.setCampaignId(campaignId);
        ad.setTitle(title);
        ad.setStorageKey(storageKey);
        ad.setTargetLocale(targetLocale);
        ad.setStatus("pending");

        ad = adRepository.save(ad);
        log.info("Ad record created with ID: {} and status: {}", ad.getId(), ad.getStatus());
 
        processingQueueService.enqueueProcessingJob(ad.getId().toString());
        log.info("Ad ID: {} enqueued for processing", ad.getId());
        return Map.of("status", "accepted", "adId", ad.getId());
    }
}
