package me.psikuvit.betterads.auth;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.dto.ForgotPasswordRequest;
import me.psikuvit.betterads.auth.dto.LoginRequest;
import me.psikuvit.betterads.auth.dto.RegisterRequest;
import me.psikuvit.betterads.auth.dto.LoginResponse;
import me.psikuvit.betterads.auth.dto.MeResponse;
import me.psikuvit.betterads.auth.dto.RefreshRequest;
import me.psikuvit.betterads.auth.dto.ResetPasswordRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("POST /auth/login called for email={}", request.email());
        try {
            LoginResponse response = authService.login(request.email(), request.password());
            log.info("Login succeeded for email={}", request.email());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("Login failed for email={}: {}", request.email(), e.getMessage());
            throw e;
        }
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("POST /auth/register called for email={}, role={}", request.email(), request.role());
        try {
            LoginResponse response = authService.register(request.email(), request.password(), request.role());
            log.info("Registration succeeded for email={}", request.email());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            log.warn("Registration failed for email={}: {}", request.email(), e.getMessage());
            throw e;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("GET /auth/me called without authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.debug("GET /auth/me called for user={}", auth.getName());
        return ResponseEntity.ok(authService.me(auth.getName()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        log.debug("POST /auth/refresh called");
        LoginResponse response = authService.refresh(request.refreshToken());
        log.info("Token refreshed for email={}", response.email());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(Authentication auth, @Valid @RequestBody RefreshRequest request) {
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("POST /auth/logout called without authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authService.logout(request.refreshToken());
        log.info("Logout succeeded for user={}", auth.getName());
        return ResponseEntity.ok(Map.of("status", "logged out"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("POST /auth/forgot-password called for email={}", request.email());
        authService.forgotPassword(request.email());
        return ResponseEntity.ok(Map.of("status", "if that email exists, a reset link has been sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("POST /auth/reset-password called");
        authService.resetPassword(request.token(), request.newPassword());
        log.info("Password reset completed");
        return ResponseEntity.ok(Map.of("status", "password updated"));
    }
}