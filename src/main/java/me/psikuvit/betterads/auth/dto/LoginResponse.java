package me.psikuvit.betterads.auth.dto;

public record LoginResponse(String token, String email, String role) {}
