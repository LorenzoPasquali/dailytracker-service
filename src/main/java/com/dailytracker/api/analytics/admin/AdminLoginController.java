package com.dailytracker.api.analytics.admin;

import com.dailytracker.api.analytics.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Public login for the hidden dashboard: password from env, rate limited per IP and globally. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminLoginController {

    private final AdminTokenService tokenService;
    private final RateLimiter perIp = new RateLimiter(5, Duration.ofMinutes(5));
    private final RateLimiter global = new RateLimiter(30, Duration.ofMinutes(5));

    public record LoginRequest(@NotBlank String password) {}
    public record LoginResponse(String token, Instant expiresAt) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String ip = ClientIp.of(request);
        if (!perIp.tryAcquire(ip) || !global.tryAcquire("*")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Too many attempts"));
        }
        if (!tokenService.passwordMatches(body.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid credentials"));
        }
        AdminTokenService.Issued issued = tokenService.issue();
        return ResponseEntity.ok(new LoginResponse(issued.token(), issued.expiresAt()));
    }
}
