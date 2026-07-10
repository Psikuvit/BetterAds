package me.psikuvit.betterads.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets common hardening response headers on every request. X-Frame-Options
 * is deliberately skipped for /embed/** — that route exists specifically to
 * be framed by publisher sites, so denying framing there would break the
 * entire embed widget feature.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (!request.getRequestURI().startsWith("/embed/")) {
            response.setHeader("X-Frame-Options", "DENY");
        }
        filterChain.doFilter(request, response);
    }
}
