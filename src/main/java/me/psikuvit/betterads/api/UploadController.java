package me.psikuvit.betterads.api;

import me.psikuvit.betterads.queue.ProcessingQueueService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ProcessingQueueService processingQueueService;

    public UploadController(ProcessingQueueService processingQueueService) {
        this.processingQueueService = processingQueueService;
    }

    @PostMapping("/upload/confirm")
    public Map<String, String> confirmUpload(@RequestBody Map<String, String> payload) {
        // TODO: validate payload, create ad record and persist to DB.
        String adId = payload.getOrDefault("adId", "stub-ad-id");
        processingQueueService.enqueueProcessingJob(adId);
        return Map.of("status", "accepted", "adId", adId);
    }
}
