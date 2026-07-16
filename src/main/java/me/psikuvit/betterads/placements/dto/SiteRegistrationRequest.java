package me.psikuvit.betterads.placements.dto;

import jakarta.validation.constraints.NotBlank;

public record SiteRegistrationRequest(
        @NotBlank String name,
        String allowedOrigin,
        String bundleId
) {}
