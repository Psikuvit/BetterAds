package me.psikuvit.betterads.auth.dto;

import me.psikuvit.betterads.storage.dto.Role;

public record LoginResponse(String token, String email, Role role) {}
