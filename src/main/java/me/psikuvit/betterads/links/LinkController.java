package me.psikuvit.betterads.links;

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
        String cacheKey = "ad:variants:" + adId;
        List<String> cached = redis.opsForList().range(cacheKey, 0, -1);
        if (cached != null && !cached.isEmpty()) return cached;

        List<String> variants = adVersionRepository.findByAdId(adId)
                .stream()
                .map(AdVersion::getStorageKey)
                .collect(Collectors.toList());

        if (!variants.isEmpty()) {
            redis.opsForList().rightPushAll(cacheKey, variants);
        }

        return variants;
    }
}
