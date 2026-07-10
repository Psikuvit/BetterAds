package me.psikuvit.betterads.auth;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.exceptions.AuthenticationException;
import me.psikuvit.betterads.storage.dto.Role;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> {
                    log.warn("No User row found for authenticated principal email={}", auth.getName());
                    return new AuthenticationException("User not found for authenticated principal");
                });
    }

    public boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.ADMIN.name()));
    }
}
