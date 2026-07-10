package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/campaigns")
@Slf4j
public class CampaignController {

    private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "paused", "completed");

    private final CampaignRepository campaignRepository;
    private final AdRepository adRepository;
    private final ViewRepository viewRepository;

    public CampaignController(CampaignRepository campaignRepository,
                               AdRepository adRepository,
                               ViewRepository viewRepository) {
        this.campaignRepository = campaignRepository;
        this.adRepository = adRepository;
        this.viewRepository = viewRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<Campaign> create(@RequestBody Map<String, Object> body, Authentication auth) {
        Campaign campaign = new Campaign();
        campaign.setName((String) body.getOrDefault("name", ""));
        campaign.setStatus("draft");
        Object budgetRaw = body.get("budget");
        campaign.setBudget(budgetRaw != null ? new BigDecimal(budgetRaw.toString()) : BigDecimal.ZERO);
        // advertiser ID would normally come from the JWT subject lookup; use 0 as placeholder
        campaign.setAdvertiserId(0L);
        campaign = campaignRepository.save(campaign);
        log.info("Campaign created: id={} by {}", campaign.getId(), auth.getName());
        return ResponseEntity.ok(campaign);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public List<Campaign> list(Authentication auth) {
        // ADMIN sees all; ADVERTISER filtering would need user-id lookup from email
        return campaignRepository.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<Campaign> get(@PathVariable Long id) {
        return campaignRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be one of: " + VALID_STATUSES));
        }
        return campaignRepository.findById(id).map(campaign -> {
            campaign.setStatus(newStatus);
            campaignRepository.save(campaign);
            log.info("Campaign {} status updated to {}", id, newStatus);
            return ResponseEntity.ok(Map.of("campaignId", id, "status", newStatus));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> analytics(@PathVariable Long id) {
        return campaignRepository.findById(id).map(campaign -> {
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
}
