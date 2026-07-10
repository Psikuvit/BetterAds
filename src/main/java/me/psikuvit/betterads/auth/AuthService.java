package me.psikuvit.betterads.auth;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.dto.LoginResponse;
import me.psikuvit.betterads.auth.dto.MeResponse;
import me.psikuvit.betterads.auth.exceptions.AuthenticationException;
import me.psikuvit.betterads.auth.exceptions.UserAlreadyExistsException;
import me.psikuvit.betterads.security.JwtTokenProvider;
import me.psikuvit.betterads.security.TokenHasher;
import me.psikuvit.betterads.storage.dto.Role;
import me.psikuvit.betterads.storage.entities.PasswordResetToken;
import me.psikuvit.betterads.storage.entities.RefreshToken;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.PasswordResetTokenRepository;
import me.psikuvit.betterads.storage.repositories.RefreshTokenRepository;
import me.psikuvit.betterads.storage.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
public class AuthService {
    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TokenHasher tokenHasher;
    private final long refreshExpirationMs;
    private final long resetExpirationMs;

    public AuthService(UserRepository userRepository, JwtTokenProvider tokenProvider, PasswordEncoder passwordEncoder,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       TokenHasher tokenHasher,
                       @Value("${app.auth.refresh-expiration-ms:604800000}") long refreshExpirationMs,
                       @Value("${app.auth.reset-expiration-ms:1800000}") long resetExpirationMs) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.tokenHasher = tokenHasher;
        this.refreshExpirationMs = refreshExpirationMs;
        this.resetExpirationMs = resetExpirationMs;
    }

    public LoginResponse login(String email, String password) {
        log.info("Login attempt for email: {}", email);
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            log.warn("Login failed: User not found for email: {}", email);
            throw new AuthenticationException("Invalid email or password");
        }

        User u = user.get();
        if (!passwordEncoder.matches(password, u.getPasswordHash())) {
            log.warn("Login failed: Invalid password for email: {}", email);
            throw new AuthenticationException("Invalid email or password");
        }

        String token = tokenProvider.generateToken(u.getEmail(), u.getRole());
        String refreshToken = issueRefreshToken(u.getId());
        log.info("Login successful for email: {}", email);
        return new LoginResponse(token, refreshToken, u.getEmail(), u.getRole());
    }

    public LoginResponse register(String email, String password, Role role) {
        log.info("Registration attempt for email: {}", email);
        if (userRepository.findByEmail(email).isPresent()) {
            log.warn("Registration failed: Email already exists: {}", email);
            throw new UserAlreadyExistsException("Email already registered");
        }

        User user = new User(
                email,
                passwordEncoder.encode(password),
                role != null ? role : Role.ADVERTISER
        );
        userRepository.save(user);
        log.info("User registered successfully with email: {} and role: {}", email, user.getRole());

        String token = tokenProvider.generateToken(user.getEmail(), user.getRole());
        String refreshToken = issueRefreshToken(user.getId());
        return new LoginResponse(token, refreshToken, user.getEmail(), user.getRole());
    }

    public MeResponse me(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("User not found for authenticated principal"));
        return new MeResponse(user.getId(), user.getEmail(), user.getRole());
    }

    public LoginResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .filter(rt -> rt.getRevokedAt() == null && rt.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AuthenticationException("Invalid or expired refresh token"));

        // Rotate: the presented refresh token is single-use.
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new AuthenticationException("User no longer exists"));

        String accessToken = tokenProvider.generateToken(user.getEmail(), user.getRole());
        String newRefreshToken = issueRefreshToken(user.getId());
        log.info("Refreshed access token for userId={}", user.getId());
        return new LoginResponse(accessToken, newRefreshToken, user.getEmail(), user.getRole());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .ifPresent(rt -> {
                    rt.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(rt);
                    log.info("Logged out userId={}", rt.getUserId());
                });
    }

    public void forgotPassword(String email) {
        // Always behave the same regardless of whether the email exists, to avoid user enumeration.
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = tokenHasher.generateOpaqueToken();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUserId(user.getId());
            resetToken.setTokenHash(tokenHasher.hash(rawToken));
            resetToken.setExpiresAt(Instant.now().plusMillis(resetExpirationMs));
            passwordResetTokenRepository.save(resetToken);
            log.info("Password reset requested for {}. Reset link: /auth/reset-password?token={}", email, rawToken);
        });
    }

    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .filter(rt -> rt.getUsedAt() == null && rt.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AuthenticationException("Invalid or expired reset token"));

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new AuthenticationException("User no longer exists"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);
        log.info("Password reset completed for userId={}", user.getId());
    }

    private String issueRefreshToken(Long userId) {
        String rawToken = tokenHasher.generateOpaqueToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(userId);
        refreshToken.setTokenHash(tokenHasher.hash(rawToken));
        refreshToken.setExpiresAt(Instant.now().plusMillis(refreshExpirationMs));
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }
}
