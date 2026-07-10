package me.psikuvit.betterads.auth.dto;

import me.psikuvit.betterads.storage.dto.Role;

public record LoginResponse(String token, String refreshToken, String email, Role role) {}
