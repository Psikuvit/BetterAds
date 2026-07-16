package me.psikuvit.betterads.placements.dto;

import me.psikuvit.betterads.storage.dto.SiteStatus;

import java.time.Instant;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        String allowedOrigin,
        String bundleId,
        SiteStatus status,
        Instant createdAt
) {}
