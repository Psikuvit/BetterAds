package me.psikuvit.betterads.placements.dto;

import jakarta.validation.constraints.NotNull;
import me.psikuvit.betterads.storage.dto.SessionEventType;

public record EventRequest(
        @NotNull SessionEventType eventType,
        String errorMessage
) {}
