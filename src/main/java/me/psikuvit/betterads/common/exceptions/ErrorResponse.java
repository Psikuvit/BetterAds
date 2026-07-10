package me.psikuvit.betterads.common.exceptions;

import java.time.Instant;

public record ErrorResponse(String error, int status, String path, Instant timestamp) {}
