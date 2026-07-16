package me.psikuvit.betterads.placements.dto;

import me.psikuvit.betterads.storage.dto.PaceStatus;

public record SelectResponse(Long adId, PaceStatus paceStatus) {}
