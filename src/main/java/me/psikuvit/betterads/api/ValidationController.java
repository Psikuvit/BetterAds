package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.validation.dto.HumanReviewQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ads")
@Slf4j
public class ValidationController {

    private final AdRepository adRepository;
    private final HumanReviewQueue humanReviewQueue;

    public ValidationController(AdRepository adRepository, HumanReviewQueue humanReviewQueue) {
        this.adRepository = adRepository;
        this.humanReviewQueue = humanReviewQueue;
    }

    @GetMapping("/{id}/validation")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> validationStatus(@PathVariable Long id) {
        return adRepository.findById(id)
                .map(ad -> {
                    boolean inHumanReview = humanReviewQueue.contains(id.toString());
                    log.info("Validation status queried for adId={}: status={}", id, ad.getStatus());
                    return ResponseEntity.ok(Map.of(
                            "adId", id,
                            "status", ad.getStatus(),
                            "inHumanReview", inHumanReview
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
