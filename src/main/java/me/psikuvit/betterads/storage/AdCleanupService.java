package me.psikuvit.betterads.storage;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.Ad;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdLinkRepository;
import me.psikuvit.betterads.storage.repositories.AdRepository;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import me.psikuvit.betterads.storage.repositories.ViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AdCleanupService {

    private final StorageService storageService;
    private final AdRepository adRepository;
    private final AdVersionRepository adVersionRepository;
    private final AdLinkRepository adLinkRepository;
    private final ViewRepository viewRepository;

    public AdCleanupService(StorageService storageService,
                            AdRepository adRepository,
                            AdVersionRepository adVersionRepository,
                            AdLinkRepository adLinkRepository,
                            ViewRepository viewRepository) {
        this.storageService = storageService;
        this.adRepository = adRepository;
        this.adVersionRepository = adVersionRepository;
        this.adLinkRepository = adLinkRepository;
        this.viewRepository = viewRepository;
    }

    /**
     * Fully deletes an ad: S3 files (original + all version variants),
     * views, ad versions, embed link, and the ad entity itself.
     */
    @Transactional
    public void deleteAd(Ad ad) {
        Long adId = ad.getId();

        List<AdVersion> versions = adVersionRepository.findByAdId(adId);
        List<Long> versionIds = versions.stream().map(AdVersion::getId).toList();

        if (!versionIds.isEmpty()) {
            viewRepository.deleteByAdVersionIdIn(versionIds);
            log.debug("Deleted views for adId={}, count={}", adId, versionIds.size());
        }

        for (AdVersion v : versions) {
            String key = extractStorageKey(v.getStorageKey());
            storageService.deleteFile(key);
        }
        log.debug("Deleted {} version S3 files for adId={}", versions.size(), adId);

        adVersionRepository.deleteByAdId(adId);

        adLinkRepository.deleteByAdId(adId);

        storageService.deleteFile(extractStorageKey(ad.getStorageKey()));

        adRepository.delete(ad);
        log.info("Fully cleaned up adId={}", adId);
    }

    /**
     * Deletes all ads in a campaign — used when budget is exhausted.
     */
    @Transactional
    public void deleteCampaignAds(Long campaignId) {
        List<Ad> ads = adRepository.findByCampaignId(campaignId);
        for (Ad ad : ads) {
            deleteAd(ad);
        }
        log.info("Deleted {} ads for campaignId={}", ads.size(), campaignId);
    }

    private String extractStorageKey(String rawKey) {
        int idx = rawKey.indexOf("::");
        return idx == -1 ? rawKey : rawKey.substring(0, idx);
    }
}
