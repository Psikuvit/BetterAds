package me.psikuvit.betterads.placements;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.fraud.exceptions.TooManyRequestsException;
import me.psikuvit.betterads.placements.dto.SelectRequest;
import me.psikuvit.betterads.placements.dto.SelectResponse;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.dto.PaceStatus;
import me.psikuvit.betterads.storage.dto.SiteStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Server-driven ad selection (Phase 6), replacing CampaignPlayer.tsx's
 * client-side "fetch every live ad, cycle every 30s" logic with a decision
 * made per request: which one ad (of a campaign's live ads) to serve next.
 *
 * Frequency capping is real and enforced (a hard filter, fail-open only if
 * every candidate is capped, so a slot is never left blank). Pacing is
 * informational only, not enforced — see the plan/README for why: every
 * candidate ad here belongs to the *same* campaign (this product has no
 * cross-campaign ad marketplace), so a campaign-level pacing score can't
 * influence *which ad* is picked, only whether this campaign's spend is
 * ahead of or behind its flight-window schedule. Real throttling would need
 * windowed spend tracking wired into BillingService — bigger, not done here.
 */
@Service
@Slf4j
public class AdSelectionService {

    private static final String REDIS_FREQ_KEY_PREFIX = "placement:freq:";

    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final CampaignRepository campaignRepository;
    private final AdRepository adRepository;
    private final FraudService fraudService;
    private final StringRedisTemplate redis;
    private final Duration frequencyCapWindow;
    private final int frequencyCapMaxViews;
    private final double pacingThreshold;

    public AdSelectionService(SiteRepository siteRepository,
                              SiteService siteService,
                              CampaignRepository campaignRepository,
                              AdRepository adRepository,
                              FraudService fraudService,
                              StringRedisTemplate redis,
                              @Value("${app.placements.frequency-cap-window-hours:24}") long frequencyCapWindowHours,
                              @Value("${app.placements.frequency-cap-max-views-per-window:3}") int frequencyCapMaxViews,
                              @Value("${app.placements.pacing-threshold:0.1}") double pacingThreshold) {
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.campaignRepository = campaignRepository;
        this.adRepository = adRepository;
        this.fraudService = fraudService;
        this.redis = redis;
        this.frequencyCapWindow = Duration.ofHours(frequencyCapWindowHours);
        this.frequencyCapMaxViews = frequencyCapMaxViews;
        this.pacingThreshold = pacingThreshold;
    }

    public SelectResponse selectAd(String siteKey, SelectRequest request, HttpServletRequest httpRequest, String ip) {
        Site site = siteRepository.findBySiteKey(siteKey)
                .filter(s -> s.getStatus() == SiteStatus.ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("Site not found for key: " + siteKey));

        siteService.validateOrigin(site, httpRequest, request.bundleId());

        if (fraudService.isLikelyFraud(ip)) {
            log.warn("Blocked select request for site={} from ip={}", site.getSiteKey(), ip);
            throw new TooManyRequestsException("Too many requests from this IP");
        }
        if (fraudService.isCampaignOverVelocity(request.campaignId())) {
            log.warn("Blocked select request for campaignId={} — exceeded view velocity cap", request.campaignId());
            throw new TooManyRequestsException("This campaign is receiving too many requests, please try again shortly");
        }

        Campaign campaign = campaignRepository.findById(request.campaignId())
                .orElseThrow(() -> new NoSuchElementException("Campaign not found: " + request.campaignId()));

        List<Ad> liveAds = adRepository.findByCampaignIdAndStatus(request.campaignId(), AdStatus.LIVE);
        if (liveAds.isEmpty()) {
            throw new NoSuchElementException("No live ads in campaign: " + request.campaignId());
        }

        String viewerKey = (request.viewerId() != null && !request.viewerId().isBlank()) ? request.viewerId() : ip;

        List<Ad> eligible = liveAds.stream().filter(ad -> !isFrequencyCapped(viewerKey, ad.getId())).toList();
        // Fail open: if every ad is capped for this viewer, still serve one
        // rather than leaving the slot blank.
        List<Ad> candidates = eligible.isEmpty() ? liveAds : eligible;
        Ad chosen = candidates.getFirst();

        recordImpressionAttempt(viewerKey, chosen.getId());

        PaceStatus paceStatus = computePaceStatus(campaign);
        return new SelectResponse(chosen.getId(), paceStatus);
    }

    private boolean isFrequencyCapped(String viewerKey, Long adId) {
        String key = REDIS_FREQ_KEY_PREFIX + viewerKey + ":" + adId;
        String raw = redis.opsForValue().get(key);
        long count = raw != null ? Long.parseLong(raw) : 0;
        return count >= frequencyCapMaxViews;
    }

    private void recordImpressionAttempt(String viewerKey, Long adId) {
        String key = REDIS_FREQ_KEY_PREFIX + viewerKey + ":" + adId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, frequencyCapWindow);
        }
    }

    PaceStatus computePaceStatus(Campaign campaign) {
        Instant startsAt = campaign.getStartsAt();
        Instant endsAt = campaign.getEndsAt();
        BigDecimal budget = campaign.getBudget();
        if (startsAt == null || endsAt == null || budget == null || budget.compareTo(BigDecimal.ZERO) <= 0) {
            return PaceStatus.UNPACED;
        }

        long totalDurationMs = Duration.between(startsAt, endsAt).toMillis();
        if (totalDurationMs <= 0) {
            return PaceStatus.UNPACED;
        }

        double elapsedFraction = clamp01((double) Duration.between(startsAt, Instant.now()).toMillis() / totalDurationMs);
        double spentFraction = clamp01(campaign.getSpent().doubleValue() / budget.doubleValue());
        double diff = spentFraction - elapsedFraction;

        if (diff > pacingThreshold) {
            return PaceStatus.AHEAD;
        }
        if (diff < -pacingThreshold) {
            return PaceStatus.BEHIND;
        }
        return PaceStatus.ON_PACE;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
