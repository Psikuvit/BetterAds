package me.psikuvit.betterads.api;

import com.amazonaws.services.s3.model.ObjectMetadata;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.api.dto.ConfirmUploadRequest;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.queue.ProcessingQueueService;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.storage.UploadPolicy;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Slf4j
public class UploadController {

    private final ProcessingQueueService processingQueueService;
    private final AdRepository adRepository;
    private final CampaignRepository campaignRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final long maxSizeBytes;

    public UploadController(ProcessingQueueService processingQueueService,
                            AdRepository adRepository,
                            CampaignRepository campaignRepository,
                            StorageService storageService,
                            CurrentUserService currentUserService,
                            @Value("${app.upload.max-size-bytes:209715200}") long maxSizeBytes) {
        this.processingQueueService = processingQueueService;
        this.adRepository = adRepository;
        this.campaignRepository = campaignRepository;
        this.storageService = storageService;
        this.currentUserService = currentUserService;
        this.maxSizeBytes = maxSizeBytes;
    }

    @PostMapping("/upload/confirm")
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<?> confirmUpload(@Valid @RequestBody ConfirmUploadRequest request, Authentication auth) {
        log.info("Received upload confirmation request: {}", request);

        Optional<Campaign> campaignOpt = campaignRepository.findById(request.campaignId());
        if (campaignOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Campaign campaign = campaignOpt.get();
        User user = currentUserService.resolve(auth);
        if (!currentUserService.isAdmin(auth) &&
                (campaign.getAdvertiserId() == null || !campaign.getAdvertiserId().equals(user.getId()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have access to this campaign"));
        }

        Optional<ObjectMetadata> metadata = storageService.getObjectMetadata(request.storageKey());
        if (metadata.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "storageKey does not reference an uploaded object"));
        }
        ObjectMetadata meta = metadata.get();
        if (meta.getContentLength() > maxSizeBytes) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "uploaded object exceeds max size of " + maxSizeBytes + " bytes"));
        }
        if (meta.getContentType() == null || !UploadPolicy.ALLOWED_CONTENT_TYPES.contains(meta.getContentType())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "uploaded object content type is not an allowed video type"));
        }

        Ad ad = new Ad();
        ad.setCampaignId(request.campaignId());
        ad.setTitle(request.title());
        ad.setStorageKey(request.storageKey());
        ad.setTargetLocale(request.targetLocale() != null ? request.targetLocale() : "en");
        ad.setStatus("pending");

        ad = adRepository.save(ad);
        log.info("Ad record created with ID: {} and status: {}", ad.getId(), ad.getStatus());

        processingQueueService.enqueueProcessingJob(ad.getId().toString());
        log.info("Ad ID: {} enqueued for processing", ad.getId());
        return ResponseEntity.ok(Map.of("status", "accepted", "adId", ad.getId()));
    }
}
