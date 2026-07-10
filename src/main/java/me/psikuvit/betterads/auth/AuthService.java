package me.psikuvit.betterads.auth;

import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
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
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            throw new AuthenticationException("Invalid email or password");
        }

        User u = user.get();
        if (!passwordEncoder.matches(password, u.getPasswordHash())) {
            throw new AuthenticationException("Invalid email or password");
        }

        String token = tokenProvider.generateToken(u.getEmail(), u.getRole());
        return new LoginResponse(token, u.getEmail(), u.getRole());
    }

    public LoginResponse register(String email, String password, String role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException("Email already registered");
        }

        User user = new User(
                email,
                passwordEncoder.encode(password),
                role != null ? role : "ADVERTISER"
        );
        userRepository.save(user);

        String token = tokenProvider.generateToken(user.getEmail(), user.getRole());
        return new LoginResponse(token, user.getEmail(), user.getRole());
    }
}
