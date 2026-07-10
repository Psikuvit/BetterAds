package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.worker.AdLifecycleService;
import me.psikuvit.betterads.worker.AdStatusEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@RestController
@RequestMapping("/api/ads")
@Slf4j
public class ValidationController {

    private static final Set<String> VALID_DECISIONS = Set.of("approve", "reject");

    private final AdRepository adRepository;
    private final CampaignRepository campaignRepository;
    private final CurrentUserService currentUserService;
    private final AdLifecycleService adLifecycleService;
    private final AdStatusEventPublisher eventPublisher;

    public ValidationController(AdRepository adRepository, CampaignRepository campaignRepository,
                                CurrentUserService currentUserService, AdLifecycleService adLifecycleService,
                                AdStatusEventPublisher eventPublisher) {
        this.adRepository = adRepository;
        this.campaignRepository = campaignRepository;
        this.currentUserService = currentUserService;
        this.adLifecycleService = adLifecycleService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public SseEmitter events(@PathVariable Long id) {
        if (adRepository.findById(id).isEmpty()) {
            throw new NoSuchElementException("Ad not found: " + id);
        }
        return eventPublisher.subscribe(id);
    }

    @GetMapping("/{id}/validation")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> validationStatus(@PathVariable Long id) {
        return adRepository.findById(id)
                .map(ad -> {
                    boolean inHumanReview = ad.getStatus() == AdStatus.FLAGGED;
                    log.info("Validation status queried for adId={}: status={}", id, ad.getStatus());
                    return ResponseEntity.ok(Map.of(
                            "adId", id,
                            "status", ad.getStatus(),
                            "inHumanReview", inHumanReview
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> review(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String decision = body.get("decision");
        if (decision == null || !VALID_DECISIONS.contains(decision)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "decision must be one of: " + VALID_DECISIONS));
        }
        return adRepository.findById(id).map(ad -> {
            if (ad.getStatus() != AdStatus.FLAGGED) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "ad is not pending human review (status=" + ad.getStatus() + ")"));
            }
            if ("approve".equals(decision)) {
                ad.setStatus(AdStatus.AWAITING_FEATURES);
                adRepository.save(ad);
                eventPublisher.publish(id, ad.getStatus());
            } else {
                adLifecycleService.reject(ad);
            }
            log.info("Ad ID: {} human review resolved: {}", id, decision);
            return ResponseEntity.ok(Map.of("adId", id, "status", ad.getStatus()));
        }).orElse(ResponseEntity.notFound().build());
    }

    public record SelectFeaturesRequest(List<String> locales) {}

    @PostMapping("/{id}/features")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> selectFeatures(@PathVariable Long id, @RequestBody SelectFeaturesRequest request,
                                            Authentication auth) {
        return adRepository.findById(id).map(ad -> {
            if (!canAccess(ad, auth)) {
                return forbidden();
            }
            if (ad.getStatus() != AdStatus.AWAITING_FEATURES) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "ad is not awaiting feature selection (status=" + ad.getStatus() + ")"));
            }
            List<String> locales = (request.locales() == null || request.locales().isEmpty())
                    ? List.of(ad.getTargetLocale() != null ? ad.getTargetLocale() : "en")
                    : request.locales();
            adLifecycleService.moveToLive(ad, locales);
            log.info("Ad ID: {} features selected, locales={}", id, locales);
            return ResponseEntity.ok(Map.of("adId", id, "status", ad.getStatus()));
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean canAccess(Ad ad, Authentication auth) {
        if (currentUserService.isAdmin(auth)) {
            return true;
        }
        User user = currentUserService.resolve(auth);
        return campaignRepository.findById(ad.getCampaignId())
                .map(Campaign::getAdvertiserId)
                .map(advertiserId -> advertiserId.equals(user.getId()))
                .orElse(false);
    }

    private ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You do not have access to this ad"));
    }
}
