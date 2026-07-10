package me.psikuvit.betterads.auth;

public record LoginResponse(String token, String email, String role) {}
