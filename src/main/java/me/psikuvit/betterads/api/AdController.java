package me.psikuvit.betterads.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.billing.BillingService;
import me.psikuvit.betterads.common.exceptions.ErrorResponse;
import me.psikuvit.betterads.embed.EmbedService;
import me.psikuvit.betterads.fraud.FraudService;
import me.psikuvit.betterads.fraud.ViewTokenService;
import me.psikuvit.betterads.links.LinkService;
import me.psikuvit.betterads.security.ClientIpResolver;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final String DEFAULT_LOCALE = "en";

    private final LinkService linkService;
    private final FraudService fraudService;
    private final BillingService billingService;
    private final AdRepository adRepository;
    private final AdVersionRepository adVersionRepository;
    private final EmbedService embedService;
    private final ViewTokenService viewTokenService;
    private final ClientIpResolver clientIpResolver;
    private final StorageService storageService;

    public AdController(LinkService linkService, FraudService fraudService,
                        BillingService billingService, AdRepository adRepository,
                        AdVersionRepository adVersionRepository, EmbedService embedService,
                        ViewTokenService viewTokenService, ClientIpResolver clientIpResolver,
                        StorageService storageService) {
        this.linkService = linkService;
        this.fraudService = fraudService;
        this.billingService = billingService;
        this.adRepository = adRepository;
        this.adVersionRepository = adVersionRepository;
        this.embedService = embedService;
        this.viewTokenService = viewTokenService;
        this.clientIpResolver = clientIpResolver;
        this.storageService = storageService;
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

        List<AdVersion> variants = resolveVariants(all, locale);

        // Record view against the best-matched variant
        AdVersion best = variants.getFirst();
        billingService.recordView(best.getId(), ip, deviceInfo);

        // Signed URLs, not raw storage keys — the browser can't resolve a bare S3 key.
        // Longer expiry than the upload presign since this is read-only playback content
        // and a mid-view expiry would break the video.
        List<String> urls = variants.stream()
                .map(v -> storageService.presignGetUrl(extractStorageKey(v.getStorageKey()), Duration.ofHours(2)))
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
            List<AdVersion> variants = resolveVariants(versions, locale);
            if (variants.isEmpty()) {
                return null;
            }
            AdVersion best = variants.getFirst();
            billingService.recordView(best.getId(), ip, deviceInfo);
            String url = storageService.presignGetUrl(extractStorageKey(best.getStorageKey()), Duration.ofHours(2));
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
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAd(@PathVariable Long id) {
        return adRepository.findById(id).map(ad -> {
            adRepository.delete(ad);
            log.info("Ad {} deleted by admin", id);
            return ResponseEntity.ok(Map.of("adId", id, "deleted", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    private ResponseEntity<ErrorResponse> tooManyRequests(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(message, HttpStatus.TOO_MANY_REQUESTS.value(), request.getRequestURI(), Instant.now()));
    }

    /**
     * Resolves which AdVersion(s) to serve for a requested locale:
     * 1. Exact locale match, if the requester specified one and it exists.
     * 2. The platform default locale, if present — a stable, predictable
     *    fallback rather than whatever order the DB happens to return.
     * 3. Whatever exists at all, as a last resort, so a viewer always sees something.
     */
    private List<AdVersion> resolveVariants(List<AdVersion> all, String requestedLocale) {
        if (requestedLocale != null && !requestedLocale.isBlank()) {
            List<AdVersion> exact = all.stream()
                    .filter(v -> requestedLocale.equalsIgnoreCase(v.getLocale()))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }

        List<AdVersion> defaultLocale = all.stream()
                .filter(v -> DEFAULT_LOCALE.equalsIgnoreCase(v.getLocale()))
                .toList();
        if (!defaultLocale.isEmpty()) {
            return defaultLocale;
        }

        return all;
    }

    private String extractStorageKey(String rawKey) {
        int idx = rawKey.indexOf("::");
        return idx == -1 ? rawKey : rawKey.substring(0, idx);
    }
}