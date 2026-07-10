package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/campaigns")
@Slf4j
public class CampaignController {

    private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "paused", "completed", "archived");

    private final CampaignRepository campaignRepository;
    private final AdRepository adRepository;
    private final ViewRepository viewRepository;
    private final CurrentUserService currentUserService;

    public CampaignController(CampaignRepository campaignRepository,
                               AdRepository adRepository,
                               ViewRepository viewRepository,
                               CurrentUserService currentUserService) {
        this.campaignRepository = campaignRepository;
        this.adRepository = adRepository;
        this.viewRepository = viewRepository;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<Campaign> create(@RequestBody Map<String, Object> body, Authentication auth) {
        User user = currentUserService.resolve(auth);
        Campaign campaign = new Campaign();
        campaign.setName((String) body.getOrDefault("name", ""));
        campaign.setStatus("draft");
        Object budgetRaw = body.get("budget");
        campaign.setBudget(budgetRaw != null ? new BigDecimal(budgetRaw.toString()) : BigDecimal.ZERO);
        campaign.setAdvertiserId(user.getId());
        campaign = campaignRepository.save(campaign);
        log.info("Campaign created: id={} by {}", campaign.getId(), auth.getName());
        return ResponseEntity.ok(campaign);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public Page<Campaign> list(Authentication auth, Pageable pageable) {
        if (currentUserService.isAdmin(auth)) {
            return campaignRepository.findAll(pageable);
        }
        User user = currentUserService.resolve(auth);
        return campaignRepository.findByAdvertiserId(user.getId(), pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> get(@PathVariable Long id, Authentication auth) {
        return campaignRepository.findById(id)
                .map(campaign -> {
                    if (!canAccess(campaign, auth)) {
                        return forbidden();
                    }
                    return ResponseEntity.ok(campaign);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ads")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> listAds(@PathVariable Long id, Authentication auth, Pageable pageable) {
        return campaignRepository.findById(id)
                .map(campaign -> {
                    if (!canAccess(campaign, auth)) {
                        return forbidden();
                    }
                    return ResponseEntity.ok(adRepository.findByCampaignId(id, pageable));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        // Locked read: held for the rest of this transaction so this budget
        // edit can't interleave with a concurrent recordView spend or a
        // payment webhook credit on the same campaign.
        return campaignRepository.findByIdForUpdate(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden();
            }
            if (body.containsKey("name")) {
                campaign.setName((String) body.get("name"));
            }
            if (body.containsKey("budget")) {
                BigDecimal newBudget = new BigDecimal(body.get("budget").toString());
                if (newBudget.compareTo(campaign.getSpent()) < 0) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "budget cannot be lower than amount already spent (" + campaign.getSpent() + ")"));
                }
                campaign.setBudget(newBudget);
            }
            campaignRepository.save(campaign);
            log.info("Campaign {} updated by {}", id, auth.getName());
            return ResponseEntity.ok(campaign);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        String newStatus = body.get("status");
        if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be one of: " + VALID_STATUSES));
        }
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden();
            }
            campaign.setStatus(newStatus);
            campaignRepository.save(campaign);
            log.info("Campaign {} status updated to {}", id, newStatus);
            return ResponseEntity.ok(Map.of("campaignId", id, "status", newStatus));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> analytics(@PathVariable Long id, Authentication auth) {
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden();
            }
            long totalViews = viewRepository.countViewsByCampaignId(id);
            long totalAds = adRepository.findByCampaignId(id).size();
            return ResponseEntity.ok(Map.of(
                    "campaignId", id,
                    "totalViews", totalViews,
                    "totalAds", totalAds,
                    "spent", campaign.getSpent(),
                    "budget", campaign.getBudget(),
                    "status", campaign.getStatus()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/analytics/timeseries")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> analyticsTimeseries(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "7") int days,
                                                 Authentication auth) {
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden();
            }
            Instant since = Instant.now().minus(Duration.ofDays(days));
            var series = viewRepository.viewsByDay(id, since).stream()
                    .map(row -> Map.<String, Object>of("date", row[0].toString(), "views", ((Number) row[1]).longValue()))
                    .toList();
            return ResponseEntity.ok(series);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ads/analytics")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> adsAnalytics(@PathVariable Long id, Authentication auth) {
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden();
            }
            var breakdown = viewRepository.viewsByAd(id).stream()
                    .map(row -> Map.<String, Object>of(
                            "adId", ((Number) row[0]).longValue(),
                            "title", row[1] != null ? row[1] : "",
                            "views", ((Number) row[2]).longValue()))
                    .toList();
            return ResponseEntity.ok(breakdown);
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean canAccess(Campaign campaign, Authentication auth) {
        if (currentUserService.isAdmin(auth)) {
            return true;
        }
        User user = currentUserService.resolve(auth);
        return campaign.getAdvertiserId() != null && campaign.getAdvertiserId().equals(user.getId());
    }

    private ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You do not have access to this campaign"));
    }
}
