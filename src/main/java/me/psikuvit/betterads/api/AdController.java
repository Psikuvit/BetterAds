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
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    public AdController(LinkService linkService, FraudService fraudService,
                        BillingService billingService, AdRepository adRepository,
                        AdVersionRepository adVersionRepository, EmbedService embedService,
                        ViewTokenService viewTokenService, ClientIpResolver clientIpResolver) {
        this.linkService = linkService;
        this.fraudService = fraudService;
        this.billingService = billingService;
        this.adRepository = adRepository;
        this.adVersionRepository = adVersionRepository;
        this.embedService = embedService;
        this.viewTokenService = viewTokenService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> serveAd(@PathVariable Long id,
                                     @RequestParam(required = false) String locale,
                                     @RequestParam(required = false) String vt,
                                     HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String deviceInfo = request.getHeader("User-Agent");
        boolean suspiciousUserAgent = deviceInfo == null || deviceInfo.isBlank();

        // A valid one-time view token proves this came from a genuine widget load and
        // can't be replayed, so it's trusted in place of the IP-rate-limit fallback check —
        // unless the request also has no User-Agent, in which case it doesn't get the free
        // pass and is still subject to the normal fraud check (soft signal, not a hard block).
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

        List<AdVersion> matched = locale != null && !locale.isBlank()
                ? all.stream().filter(v -> locale.equalsIgnoreCase(v.getLocale())).toList()
                : all;
        List<AdVersion> variants = matched.isEmpty() ? all : matched;

        // Record view against the best-matched variant
        AdVersion best = variants.getFirst();
        billingService.recordView(best.getId(), ip, deviceInfo);

        List<String> keys = variants.stream().map(AdVersion::getStorageKey).toList();
        log.info("Served adId={} to ip={}, variants={}", id, ip, keys.size());
        return ResponseEntity.ok(Map.of("adId", id, "variants", keys));
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

    private ResponseEntity<ErrorResponse> tooManyRequests(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(message, HttpStatus.TOO_MANY_REQUESTS.value(), request.getRequestURI(), Instant.now()));
    }
}
