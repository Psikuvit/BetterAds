package me.psikuvit.betterads.placements.dto;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        String allowedOrigin,
        String bundleId
) {}
