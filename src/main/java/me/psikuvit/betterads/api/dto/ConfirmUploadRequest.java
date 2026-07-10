package me.psikuvit.betterads.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmUploadRequest(
        @NotNull Long campaignId,
        @NotBlank String title,
        @NotBlank String storageKey,
        String targetLocale
) {}
