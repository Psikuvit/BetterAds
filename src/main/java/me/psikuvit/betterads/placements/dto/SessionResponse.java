package me.psikuvit.betterads.placements.dto;

public record SessionResponse(
        String sessionToken,
        Long adId,
        Long adVersionId,
        String videoUrl,
        String locale,
        Long durationSeconds
) {}
