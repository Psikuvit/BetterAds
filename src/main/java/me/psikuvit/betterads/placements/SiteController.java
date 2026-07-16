package me.psikuvit.betterads.placements;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.placements.dto.SiteRegistrationRequest;
import me.psikuvit.betterads.placements.dto.SiteResponse;
import me.psikuvit.betterads.storage.entities.Site;
import me.psikuvit.betterads.storage.entities.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final CurrentUserService currentUserService;

    public SiteController(SiteService siteService, CurrentUserService currentUserService) {
        this.siteService = siteService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PUBLISHER', 'ADMIN')")
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegistrationRequest request, Authentication auth) {
        User user = currentUserService.resolve(auth);
        Site site = siteService.register(user.getId(), request.name(), request.allowedOrigin(), request.bundleId());
        log.info("Site {} registered by {}", site.getId(), auth.getName());
        return ResponseEntity.ok(new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getAllowedOrigin(), site.getBundleId()));
    }
}
