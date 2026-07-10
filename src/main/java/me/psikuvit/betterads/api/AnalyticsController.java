package me.psikuvit.betterads.api;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.storage.dto.CampaignStatus;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analytics")
@Slf4j
public class AnalyticsController {

    private final CampaignRepository campaignRepository;
    private final ViewRepository viewRepository;
    private final CurrentUserService currentUserService;

    public AnalyticsController(CampaignRepository campaignRepository, ViewRepository viewRepository,
                               CurrentUserService currentUserService) {
        this.campaignRepository = campaignRepository;
        this.viewRepository = viewRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/advertiser")
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<?> advertiserSummary(Authentication auth) {
        User user = currentUserService.resolve(auth);
        List<Campaign> campaigns = campaignRepository.findByAdvertiserId(user.getId());

        BigDecimal totalSpent = campaigns.stream().map(Campaign::getSpent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBudget = campaigns.stream().map(Campaign::getBudget).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalViews = campaigns.stream().mapToLong(c -> viewRepository.countViewsByCampaignId(c.getId())).sum();
        Map<CampaignStatus, Long> campaignsByStatus = campaigns.stream()
                .collect(Collectors.groupingBy(Campaign::getStatus, Collectors.counting()));

        return ResponseEntity.ok(Map.of(
                "campaignCount", campaigns.size(),
                "campaignsByStatus", campaignsByStatus,
                "totalSpent", totalSpent,
                "totalBudget", totalBudget,
                "totalViews", totalViews
        ));
    }
}
