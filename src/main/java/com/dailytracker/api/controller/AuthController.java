package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.LoginRequest;
import com.dailytracker.api.dto.request.RegisterRequest;
import com.dailytracker.api.dto.request.TokenRefreshRequest;
import com.dailytracker.api.dto.response.AuthResponse;
import com.dailytracker.api.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/google")
    public void googleLogin(HttpServletRequest request, HttpServletResponse response,
                            @RequestParam(required = false) String popup) throws IOException {
        if ("true".equals(popup)) {
            request.getSession().setAttribute("oauth_popup", true);
        }
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

    @PostMapping("/consume-cookies")
    public ResponseEntity<AuthResponse> consumeCookies(HttpServletRequest request, HttpServletResponse response) {
        String token = null;
        String refreshToken = null;

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("auth_token".equals(cookie.getName())) {
                    token = cookie.getValue();
                } else if ("auth_refresh_token".equals(cookie.getName())) {
                    refreshToken = cookie.getValue();
                }
            }
        }

        if (token == null) {
            return ResponseEntity.status(401).build();
        }

        // Clear the cookies
        ResponseCookie clearToken = ResponseCookie.from("auth_token", "")
                .httpOnly(true).secure(true).path("/").maxAge(0).sameSite("Lax").build();
        ResponseCookie clearRefresh = ResponseCookie.from("auth_refresh_token", "")
                .httpOnly(true).secure(true).path("/").maxAge(0).sameSite("Lax").build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearToken.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());

        return ResponseEntity.ok(new AuthResponse(token, refreshToken));
    }
}
