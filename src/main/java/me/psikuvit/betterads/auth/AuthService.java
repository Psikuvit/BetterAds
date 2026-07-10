package me.psikuvit.betterads.auth;
 
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.dto.LoginResponse;
import me.psikuvit.betterads.auth.exceptions.AuthenticationException;
import me.psikuvit.betterads.auth.exceptions.UserAlreadyExistsException;
import me.psikuvit.betterads.security.JwtTokenProvider;
import me.psikuvit.betterads.storage.dto.Role;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class AuthService {
    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, JwtTokenProvider tokenProvider, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
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
        log.info("Login successful for email: {}", email);
        return new LoginResponse(token, u.getEmail(), u.getRole());
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
        return new LoginResponse(token, user.getEmail(), user.getRole());
    }
}
