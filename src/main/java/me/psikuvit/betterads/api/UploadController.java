package me.psikuvit.betterads.api;

import me.psikuvit.betterads.queue.ProcessingQueueService;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.repo.AdRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ProcessingQueueService processingQueueService;
    private final AdRepository adRepository;

    public UploadController(ProcessingQueueService processingQueueService, AdRepository adRepository) {
        this.processingQueueService = processingQueueService;
        this.adRepository = adRepository;
    }

    @PostMapping("/upload/confirm")
    public Map<String, Object> confirmUpload(@RequestBody Map<String, Object> payload) {
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

        processingQueueService.enqueueProcessingJob(ad.getId().toString());
        return Map.of("status", "accepted", "adId", ad.getId());
    }
}
