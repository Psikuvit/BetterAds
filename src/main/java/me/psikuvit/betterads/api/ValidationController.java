package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.worker.AdLifecycleService;
import me.psikuvit.betterads.worker.AdStatusEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@RestController
@RequestMapping("/api/ads")
@Slf4j
public class ValidationController {

    private static final Set<String> VALID_DECISIONS = Set.of("approve", "reject");

    private final AdRepository adRepository;
    private final AdLifecycleService adLifecycleService;
    private final AdStatusEventPublisher eventPublisher;

    public ValidationController(AdRepository adRepository, AdLifecycleService adLifecycleService,
                                AdStatusEventPublisher eventPublisher) {
        this.adRepository = adRepository;
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
                adLifecycleService.moveToLive(ad);
            } else {
                adLifecycleService.reject(ad);
            }
            log.info("Ad ID: {} human review resolved: {}", id, decision);
            return ResponseEntity.ok(Map.of("adId", id, "status", ad.getStatus()));
        }).orElse(ResponseEntity.notFound().build());
    }
}
