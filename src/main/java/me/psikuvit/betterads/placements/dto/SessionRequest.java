package me.psikuvit.betterads.placements.dto;

import jakarta.validation.constraints.NotNull;

public record SessionRequest(
        @NotNull Long adId,
        String locale,
        String bundleId
) {}
