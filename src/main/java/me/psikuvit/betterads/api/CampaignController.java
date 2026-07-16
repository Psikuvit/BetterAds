package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.dto.CampaignStatus;
import me.psikuvit.betterads.storage.entities.Ad;
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
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
@Slf4j
public class CampaignController {

    private final CampaignRepository campaignRepository;
    private final AdRepository adRepository;
    private final ViewRepository viewRepository;
    private final CurrentUserService currentUserService;
    private final EmbedService embedService;

    public CampaignController(CampaignRepository campaignRepository,
                               AdRepository adRepository,
                               ViewRepository viewRepository,
                               CurrentUserService currentUserService,
                               EmbedService embedService) {
        this.campaignRepository = campaignRepository;
        this.adRepository = adRepository;
        this.viewRepository = viewRepository;
        this.currentUserService = currentUserService;
        this.embedService = embedService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<Campaign> create(@RequestBody Map<String, Object> body, Authentication auth) {
        User user = currentUserService.resolve(auth);
        Campaign campaign = new Campaign();
        campaign.setName((String) body.getOrDefault("name", ""));
        campaign.setStatus(CampaignStatus.DRAFT);
        Object budgetRaw = body.get("budget");
        campaign.setBudget(budgetRaw != null ? new BigDecimal(budgetRaw.toString()) : BigDecimal.ZERO);
        campaign.setAdvertiserId(user.getId());
        campaign.setStartsAt(parseOptionalInstant(body.get("startsAt")));
        campaign.setEndsAt(parseOptionalInstant(body.get("endsAt")));
        campaign = campaignRepository.save(campaign);
        log.info("Campaign created: id={} by {}", campaign.getId(), auth.getName());
        return ResponseEntity.ok(campaign);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public Page<Campaign> list(Authentication auth, Pageable pageable) {
        log.debug("GET /api/campaigns called by {}", auth.getName());
        if (currentUserService.isAdmin(auth)) {
            return campaignRepository.findAll(pageable);
        }
        User user = currentUserService.resolve(auth);
        return campaignRepository.findByAdvertiserId(user.getId(), pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> get(@PathVariable Long id, Authentication auth) {
        log.debug("GET /api/campaigns/{} called by {}", id, auth.getName());
        return campaignRepository.findById(id)
                .map(campaign -> {
                    if (!canAccess(campaign, auth)) {
                        return forbidden(id, auth);
                    }
                    return ResponseEntity.ok(campaign);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ads")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> listAds(@PathVariable Long id, Authentication auth, Pageable pageable) {
        log.debug("GET /api/campaigns/{}/ads called by {}", id, auth.getName());
        return campaignRepository.findById(id)
                .map(campaign -> {
                    if (!canAccess(campaign, auth)) {
                        return forbidden(id, auth);
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
                return forbidden(id, auth);
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
            if (body.containsKey("startsAt")) {
                campaign.setStartsAt(parseOptionalInstant(body.get("startsAt")));
            }
            if (body.containsKey("endsAt")) {
                campaign.setEndsAt(parseOptionalInstant(body.get("endsAt")));
            }
            campaignRepository.save(campaign);
            log.info("Campaign {} updated by {}", id, auth.getName());
            return ResponseEntity.ok(campaign);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        String raw = body.get("status");
        CampaignStatus newStatus = parseStatus(raw);
        if (newStatus == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be one of: " + Arrays.toString(CampaignStatus.values())));
        }
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden(id, auth);
            }
            campaign.setStatus(newStatus);
            campaignRepository.save(campaign);
            log.info("Campaign {} status updated to {}", id, newStatus);
            return ResponseEntity.ok(Map.of("campaignId", id, "status", newStatus));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Instant parseOptionalInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw.toString());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("startsAt/endsAt must be ISO-8601 instants, e.g. 2026-01-01T00:00:00Z");
        }
    }

    private CampaignStatus parseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return CampaignStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @GetMapping("/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> analytics(@PathVariable Long id, Authentication auth) {
        log.debug("GET /api/campaigns/{}/analytics called by {}", id, auth.getName());
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden(id, auth);
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
        log.debug("GET /api/campaigns/{}/analytics/timeseries called by {}, days={}", id, auth.getName(), days);
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden(id, auth);
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
        log.debug("GET /api/campaigns/{}/ads/analytics called by {}", id, auth.getName());
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden(id, auth);
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

    @GetMapping("/{id}/embed")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> embed(@PathVariable Long id, Authentication auth) {
        return campaignRepository.findById(id).map(campaign -> {
            if (!canAccess(campaign, auth)) {
                return forbidden(id, auth);
            }
            Ad liveAd = adRepository.findByCampaignIdAndStatus(id, AdStatus.LIVE).stream().findFirst().orElse(null);
            if (liveAd == null) {
                return ResponseEntity.ok(Map.<String, Object>of("available", false));
            }
            var link = embedService.generateLink(liveAd.getId());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "available", true,
                    "adId", liveAd.getId(),
                    "embedUrl", embedService.embedUrl(link.getToken()),
                    "embedSnippet", embedService.embedSnippet(link.getToken()),
                    "token", link.getToken()));
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean canAccess(Campaign campaign, Authentication auth) {
        if (currentUserService.isAdmin(auth)) {
            return true;
        }
        User user = currentUserService.resolve(auth);
        return campaign.getAdvertiserId() != null && campaign.getAdvertiserId().equals(user.getId());
    }

    private ResponseEntity<Object> forbidden(Long campaignId, Authentication auth) {
        log.warn("Access denied to campaign {} for user {}", campaignId, auth.getName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You do not have access to this campaign"));
    }
}
