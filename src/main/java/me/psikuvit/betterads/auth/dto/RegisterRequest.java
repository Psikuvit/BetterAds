package me.psikuvit.betterads.auth.dto;

import me.psikuvit.betterads.storage.dto.Role;

public record RegisterRequest(String email, String password, Role role) {}
