package me.psikuvit.betterads.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = extractClientKey(request);
        if (!rateLimitService.isAllowed(key)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            // Hand-built JSON (matches ErrorResponse's shape) rather than an injected
            // ObjectMapper: this filter bean is constructed very early in the Spring
            // Security filter-chain setup, before Jackson's autoconfigured ObjectMapper
            // bean is reliably available.
            response.getWriter().write(String.format(
                    "{\"error\": \"Rate limit exceeded. Max %d requests per minute\", \"status\": 429, \"path\": \"%s\", \"timestamp\": \"%s\"}",
                    properties.getRequestsPerMinute(), escapeJson(request.getRequestURI()), Instant.now()));
            return;
        }

        response.addHeader("X-Rate-Limit-Remaining", String.valueOf(rateLimitService.getRemainingTokens(key)));
        filterChain.doFilter(request, response);
    }

    private String extractClientKey(HttpServletRequest request) {
        String userId = request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : null;
        if (userId != null) {
            return "user:" + userId;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return "ip:" + xForwardedFor.split(",")[0];
        }

        return "ip:" + request.getRemoteAddr();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
