package me.psikuvit.betterads.placements;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.dto.SiteStatus;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class SiteService {

    private final SiteRepository siteRepository;

    public SiteService(SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    public Site register(Long publisherId, String name, String allowedOrigin, String bundleId) {
        Site site = new Site();
        site.setPublisherId(publisherId);
        site.setName(name);
        site.setSiteKey("site_" + UUID.randomUUID());
        site.setAllowedOrigin(allowedOrigin);
        site.setBundleId(bundleId);
        site.setStatus(SiteStatus.ACTIVE);
        return siteRepository.save(site);
    }

    /**
     * Validates the calling web origin (Origin/Referer header) against the
     * site's registered domain, or the client-supplied bundle ID claim
     * against the registered mobile bundle ID. Neither check is real app
     * attestation (e.g. Play Integrity/App Attest) — that stays out of
     * scope, consistent with FraudService's documented non-goals. A site
     * with no allowedOrigin/bundleId registered (Phase 2 hasn't populated it
     * yet) skips the corresponding check rather than blocking every caller.
     */
    public void validateOrigin(Site site, HttpServletRequest request, String bundleIdClaim) {
        String allowedOrigin = site.getAllowedOrigin();
        if (allowedOrigin != null && !allowedOrigin.isBlank()) {
            String candidate = request.getHeader("Origin");
            if (candidate == null || candidate.isBlank()) {
                candidate = request.getHeader("Referer");
            }
            if (candidate == null || !originMatches(allowedOrigin, candidate)) {
                log.warn("Rejected session request for site {}: calling origin {} does not match registered origin {}",
                        site.getSiteKey(), candidate, allowedOrigin);
                throw new AccessDeniedException("Calling origin does not match the registered site");
            }
        }

        String allowedBundleId = site.getBundleId();
        if (allowedBundleId != null && !allowedBundleId.isBlank()) {
            if (bundleIdClaim == null || !allowedBundleId.equals(bundleIdClaim)) {
                log.warn("Rejected session request for site {}: bundle ID {} does not match registered bundle ID {}",
                        site.getSiteKey(), bundleIdClaim, allowedBundleId);
                throw new AccessDeniedException("Bundle ID does not match the registered site");
            }
        }
    }

    private boolean originMatches(String allowedOrigin, String candidate) {
        try {
            URI allowedUri = URI.create(allowedOrigin);
            URI candidateUri = URI.create(candidate);
            return Objects.equals(allowedUri.getScheme(), candidateUri.getScheme())
                    && Objects.equals(allowedUri.getHost(), candidateUri.getHost());
        } catch (IllegalArgumentException e) {
            return allowedOrigin.equalsIgnoreCase(candidate);
        }
    }
}
