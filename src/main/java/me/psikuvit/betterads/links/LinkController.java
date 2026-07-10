package me.psikuvit.betterads.links;
 
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/links")
@Slf4j
public class LinkController {

    private final AdVersionRepository adVersionRepository;
    private final StringRedisTemplate redis;

    public LinkController(AdVersionRepository adVersionRepository, StringRedisTemplate redis) {
        this.adVersionRepository = adVersionRepository;
        this.redis = redis;
    }

    @GetMapping("/{adId}")
    @PreAuthorize("hasAnyRole('PUBLISHER', 'ADVERTISER')")
    public List<String> resolve(@PathVariable Long adId) {
        log.info("Resolving ad variants for adId: {}", adId);
        String cacheKey = "ad:variants:" + adId;
        List<String> cached = redis.opsForList().range(cacheKey, 0, -1);
        if (cached != null && !cached.isEmpty()) {
            log.debug("Cache hit for adId: {}. Variants: {}", adId, cached);
            return cached;
        }
 
        log.debug("Cache miss for adId: {}. Fetching from database...", adId);
        List<String> variants = adVersionRepository.findByAdId(adId)
                .stream()
                .map(AdVersion::getStorageKey)
                .collect(Collectors.toList());
 
        if (!variants.isEmpty()) {
            log.debug("Caching {} variants for adId: {}", variants.size(), adId);
            redis.opsForList().rightPushAll(cacheKey, variants);
        } else {
            log.warn("No variants found for adId: {}", adId);
        }
 
        return variants;
    }
}
