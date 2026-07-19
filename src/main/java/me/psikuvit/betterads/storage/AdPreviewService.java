package me.psikuvit.betterads.storage;

import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a presigned playback URL for one ad, with no side effects — no
 * BillingService.recordView, no FraudService/ViewTokenService checks.
 * Shared by AdController's single-ad preview and CampaignController's
 * playlist preview, both of which exist so an advertiser can watch their
 * own ad(s) in the dashboard without it being counted as a served
 * impression and without needing a registered site key. Callers are
 * responsible for their own authorization — this only resolves media.
 */
@Service
public class AdPreviewService {

    private final AdVersionRepository adVersionRepository;
    private final AdVariantResolver adVariantResolver;
    private final StorageService storageService;

    public AdPreviewService(AdVersionRepository adVersionRepository,
                             AdVariantResolver adVariantResolver,
                             StorageService storageService) {
        this.adVersionRepository = adVersionRepository;
        this.adVariantResolver = adVariantResolver;
        this.storageService = storageService;
    }

    public record Preview(Long adId, String videoUrl, String locale) {}

    public Optional<Preview> resolve(Long adId, String locale) {
        List<AdVersion> all = adVersionRepository.findByAdId(adId);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        AdVersion best = adVariantResolver.resolveVariants(all, locale).getFirst();
        String url = storageService.presignGetUrl(StorageService.extractStorageKey(best.getStorageKey()), Duration.ofHours(2));
        return Optional.of(new Preview(adId, url, best.getLocale() != null ? best.getLocale() : ""));
    }
}
