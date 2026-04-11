package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.LoginRequest;
import com.dailytracker.api.dto.request.RegisterRequest;
import com.dailytracker.api.dto.request.TokenRefreshRequest;
import com.dailytracker.api.dto.response.AuthResponse;
import com.dailytracker.api.security.AuthCodeStore;
import com.dailytracker.api.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCodeStore authCodeStore;

    @GetMapping("/google")
    public void googleLogin(HttpServletResponse response) throws IOException {
        response.sendRedirect("/oauth2/authorization/google");
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        Integer userId = authService.register(request);
        return ResponseEntity.status(201)
                .body(Map.of("message", "Usuário criado com sucesso!", "userId", userId));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request.refreshToken()));
    }

    @PostMapping("/exchange-code")
    public ResponseEntity<AuthResponse> exchangeCode(@RequestBody Map<String, String> request) {
        String code = request.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String[] tokens = authCodeStore.consumeCode(code);
        if (tokens == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new AuthResponse(tokens[0], tokens[1]));
    }
}
