package me.psikuvit.betterads.auth;

import me.psikuvit.betterads.auth.dto.LoginRequest;
import me.psikuvit.betterads.auth.dto.RegisterRequest;
import me.psikuvit.betterads.auth.dto.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request.email(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@RequestBody RegisterRequest request) {
        LoginResponse response = authService.register(request.email(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
