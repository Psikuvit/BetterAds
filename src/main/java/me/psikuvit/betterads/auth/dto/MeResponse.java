package me.psikuvit.betterads.auth.dto;

import me.psikuvit.betterads.storage.dto.Role;

public record MeResponse(Long id, String email, Role role) {}
