package me.psikuvit.betterads.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.billing.BillingService;
import me.psikuvit.betterads.common.exceptions.ErrorResponse;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.fraud.ViewTokenService;
import me.psikuvit.betterads.links.LinkService;
import me.psikuvit.betterads.security.ClientIpResolver;
import me.psikuvit.betterads.storage.AdCleanupService;
import me.psikuvit.betterads.storage.AdVariantResolver;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/ads")
@Slf4j
public class AdController {

    private final LinkService linkService;
    private final FraudService fraudService;
    private final BillingService billingService;
    private final AdRepository adRepository;
    private final AdVersionRepository adVersionRepository;
    private final EmbedService embedService;
    private final ViewTokenService viewTokenService;
    private final ClientIpResolver clientIpResolver;
    private final StorageService storageService;
    private final AdCleanupService adCleanupService;
    private final CurrentUserService currentUserService;
    private final CampaignRepository campaignRepository;
    private final AdVariantResolver adVariantResolver;

    public AdController(LinkService linkService, FraudService fraudService,
                        BillingService billingService, AdRepository adRepository,
                        AdVersionRepository adVersionRepository, EmbedService embedService,
                        ViewTokenService viewTokenService, ClientIpResolver clientIpResolver,
                        StorageService storageService, AdCleanupService adCleanupService,
                        CurrentUserService currentUserService, CampaignRepository campaignRepository,
                        AdVariantResolver adVariantResolver) {
        this.linkService = linkService;
        this.fraudService = fraudService;
        this.billingService = billingService;
        this.adRepository = adRepository;
        this.adVersionRepository = adVersionRepository;
        this.embedService = embedService;
        this.viewTokenService = viewTokenService;
        this.clientIpResolver = clientIpResolver;
        this.storageService = storageService;
        this.adCleanupService = adCleanupService;
        this.currentUserService = currentUserService;
        this.campaignRepository = campaignRepository;
        this.adVariantResolver = adVariantResolver;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> serveAd(@PathVariable Long id,
                                     @RequestParam(required = false) String locale,
                                     @RequestParam(required = false) String vt,
                                     HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String deviceInfo = request.getHeader("User-Agent");
        boolean suspiciousUserAgent = deviceInfo == null || deviceInfo.isBlank();

        boolean trustedByToken = !suspiciousUserAgent && viewTokenService.validateAndConsume(vt, id);
        if (!trustedByToken && fraudService.isLikelyFraud(ip)) {
            log.warn("Blocked fraudulent impression for adId={} from ip={}", id, ip);
            return tooManyRequests(request, "Too many requests from this IP");
        }

        Long campaignId = adRepository.findById(id).map(Ad::getCampaignId).orElse(null);
        if (campaignId != null && fraudService.isCampaignOverVelocity(campaignId)) {
            log.warn("Blocked impression for adId={} — campaign {} exceeded view velocity cap", id, campaignId);
            return tooManyRequests(request, "This ad is receiving too many requests, please try again shortly");
        }

        // Always load from DB so we have real IDs for billing
        List<AdVersion> all = adVersionRepository.findByAdId(id);
        if (all.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<AdVersion> variants = adVariantResolver.resolveVariants(all, locale);

        // Record view against the best-matched variant
        AdVersion best = variants.getFirst();
        billingService.recordView(best.getId(), ip, deviceInfo);

        // Signed URLs, not raw storage keys — the browser can't resolve a bare S3 key.
        // Longer expiry than the upload presign since this is read-only playback content
        // and a mid-view expiry would break the video.
        List<String> urls = variants.stream()
                .map(v -> storageService.presignGetUrl(StorageService.extractStorageKey(v.getStorageKey()), Duration.ofHours(2)))
                .toList();

        log.info("Served adId={} to ip={}, requestedLocale={}, resolvedLocale={}, variants={}",
                id, ip, locale, best.getLocale(), urls.size());
        return ResponseEntity.ok(Map.of("adId", id, "variants", urls));
    }

    @GetMapping("/{id}/playlist")
    public ResponseEntity<?> servePlaylist(@PathVariable Long id,
                                           @RequestParam(required = false) String locale,
                                           @RequestParam(required = false) String vt,
                                           HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String deviceInfo = request.getHeader("User-Agent");
        boolean suspiciousUserAgent = deviceInfo == null || deviceInfo.isBlank();

        Ad seedAd = adRepository.findById(id).orElse(null);
        if (seedAd == null) {
            return ResponseEntity.notFound().build();
        }

        Long campaignId = seedAd.getCampaignId();

        boolean trustedByToken = !suspiciousUserAgent && viewTokenService.validateAndConsume(vt, id);
        if (!trustedByToken && fraudService.isLikelyFraud(ip)) {
            log.warn("Blocked fraudulent playlist request from ip={}", ip);
            return tooManyRequests(request, "Too many requests from this IP");
        }
        if (campaignId != null && fraudService.isCampaignOverVelocity(campaignId)) {
            log.warn("Blocked playlist request — campaign {} exceeded view velocity cap", campaignId);
            return tooManyRequests(request, "This ad is receiving too many requests, please try again shortly");
        }

        List<Ad> allAds = adRepository.findByCampaignIdAndStatus(campaignId, AdStatus.LIVE);
        if (allAds.isEmpty()) {
            return ResponseEntity.ok(Map.of("ads", List.of()));
        }

        List<Map<String, Object>> playlist = allAds.stream().map(ad -> {
            List<AdVersion> versions = adVersionRepository.findByAdId(ad.getId());
            List<AdVersion> variants = adVariantResolver.resolveVariants(versions, locale);
            if (variants.isEmpty()) {
                return null;
            }
            AdVersion best = variants.getFirst();
            billingService.recordView(best.getId(), ip, deviceInfo);
            String url = storageService.presignGetUrl(StorageService.extractStorageKey(best.getStorageKey()), Duration.ofHours(2));
            String token = viewTokenService.issueToken(ad.getId());
            return Map.<String, Object>of(
                    "adId", ad.getId(),
                    "url", url,
                    "locale", best.getLocale() != null ? best.getLocale() : "",
                    "vt", token);
        }).filter(Objects::nonNull).toList();

        log.info("Served playlist for campaignId={} ({} ads) to ip={}, requestedLocale={}",
                campaignId, playlist.size(), ip, locale);
        return ResponseEntity.ok(Map.of("ads", playlist));
    }

    @GetMapping("/{id}/link")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> getLink(@PathVariable Long id) {
        return embedService.findByAdId(id)
                .map(link -> {
                    String url = embedService.embedUrl(link.getToken());
                    String snippet = embedService.embedSnippet(link.getToken());
                    return ResponseEntity.ok(Map.of("embedUrl", url, "embedSnippet", snippet, "token", link.getToken()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ADVERTISER')")
    public ResponseEntity<?> deleteAd(@PathVariable Long id, Authentication auth) {
        return adRepository.findById(id).map(ad -> {
            if (!currentUserService.isAdmin(auth)) {
                boolean owns = campaignRepository.findById(ad.getCampaignId())
                        .map(Campaign::getAdvertiserId)
                        .map(uid -> uid.equals(currentUserService.resolve(auth).getId()))
                        .orElse(false);
                if (!owns) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .<Object>body(Map.of("error", "You do not have access to this ad"));
                }
            }
            adCleanupService.deleteAd(ad);
            log.info("Ad {} deleted by {}", id, auth.getName());
            return ResponseEntity.ok(Map.of("adId", id, "deleted", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<ErrorResponse> tooManyRequests(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(message, HttpStatus.TOO_MANY_REQUESTS.value(), request.getRequestURI(), Instant.now()));
    }

}