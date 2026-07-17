package me.psikuvit.betterads.placements;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.placements.dto.SiteRegistrationRequest;
import me.psikuvit.betterads.placements.dto.SiteResponse;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.SiteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal self-registration so Phase 1 (session + event API) is testable
 * end-to-end without Phase 2's publisher-facing dashboard page. Phase 2 adds
 * the full registration UI and dynamic CORS wiring on top of this same
 * Site entity.
 */
@RestController
@RequestMapping("/api/sites")
@Slf4j
public class SiteController {

    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final CurrentUserService currentUserService;

    public SiteController(SiteService siteService, SiteRepository siteRepository, CurrentUserService currentUserService) {
        this.siteService = siteService;
        this.siteRepository = siteRepository;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegistrationRequest request, Authentication auth) {
        User user = currentUserService.resolve(auth);
        Site site = siteService.register(user.getId(), request.name(), request.allowedOrigin(), request.bundleId());
        log.info("Site {} registered by {}", site.getId(), auth.getName());
        return ResponseEntity.ok(toResponse(site));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public Page<SiteResponse> list(Authentication auth, Pageable pageable) {
        User user = currentUserService.resolve(auth);
        Page<Site> sites = currentUserService.isAdmin(auth)
                ? siteRepository.findAll(pageable)
                : siteRepository.findByPublisherId(user.getId(), pageable);
        return sites.map(this::toResponse);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADVERTISER', 'ADMIN')")
    public ResponseEntity<?> get(@PathVariable Long id, Authentication auth) {
        return siteRepository.findById(id).map(site -> {
            if (!canAccess(site, auth)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .<Object>body(Map.of("error", "You do not have access to this site"));
            }
            return ResponseEntity.ok(toResponse(site));
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean canAccess(Site site, Authentication auth) {
        if (currentUserService.isAdmin(auth)) {
            return true;
        }
        User user = currentUserService.resolve(auth);
        return site.getPublisherId() != null && site.getPublisherId().equals(user.getId());
    }

    private SiteResponse toResponse(Site site) {
        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getAllowedOrigin(), site.getBundleId(),
                site.getStatus(), site.getCreatedAt());
    }
}
