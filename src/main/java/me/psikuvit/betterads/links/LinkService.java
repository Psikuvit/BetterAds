package me.psikuvit.betterads.links;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.AdVersion;
import me.psikuvit.betterads.storage.repositories.AdVersionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LinkService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AdVersionRepository adVersionRepository;
    private final StringRedisTemplate redis;

    public LinkService(AdVersionRepository adVersionRepository, StringRedisTemplate redis) {
        this.adVersionRepository = adVersionRepository;
        this.redis = redis;
    }

    public List<AdVersion> resolveVariants(Long adId, String locale) {
        List<AdVersion> all = getOrLoadVariants(adId);
        if (locale != null && !locale.isBlank()) {
            List<AdVersion> matched = all.stream()
                    .filter(v -> locale.equalsIgnoreCase(v.getLocale()))
                    .collect(Collectors.toList());
            return matched.isEmpty() ? all : matched;
        }
        return all;
    }

    public List<String> resolveStorageKeys(Long adId, String locale) {
        return resolveVariants(adId, locale).stream()
                .map(AdVersion::getStorageKey)
                .collect(Collectors.toList());
    }

    private List<AdVersion> getOrLoadVariants(Long adId) {
        String cacheKey = "ad:variants:" + adId;
        List<String> cached = redis.opsForList().range(cacheKey, 0, -1);
        if (cached != null && !cached.isEmpty()) {
            log.debug("Cache hit for adId={}", adId);
            // Reconstruct lightweight AdVersion objects from cached storage keys
            return cached.stream().map(key -> {
                AdVersion v = new AdVersion();
                v.setAdId(adId);
                v.setStorageKey(key);
                return v;
            }).collect(Collectors.toList());
        }

        log.debug("Cache miss for adId={}, loading from DB", adId);
        List<AdVersion> versions = adVersionRepository.findByAdId(adId);
        if (!versions.isEmpty()) {
            List<String> keys = versions.stream()
                    .map(AdVersion::getStorageKey)
                    .collect(Collectors.toList());
            redis.opsForList().rightPushAll(cacheKey, keys);
            redis.expire(cacheKey, CACHE_TTL);
        }
        return versions;
    }

    public void invalidateCache(Long adId) {
        redis.delete("ad:variants:" + adId);
    }
}
