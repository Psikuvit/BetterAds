package me.psikuvit.betterads.storage.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignRequest(@NotBlank String key, @NotBlank String contentType) {}
