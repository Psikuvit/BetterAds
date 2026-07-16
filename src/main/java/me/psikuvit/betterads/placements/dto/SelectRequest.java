package me.psikuvit.betterads.placements.dto;

import jakarta.validation.constraints.NotNull;

public record SelectRequest(
        @NotNull Long campaignId,
        /** Opaque client-generated viewer id for frequency capping. Falls back to resolved IP if omitted (coarser — shared across viewers behind the same NAT/proxy). */
        String viewerId,
        String bundleId
) {}
