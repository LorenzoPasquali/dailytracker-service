package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.LoginRequest;
import com.dailytracker.api.dto.request.RegisterRequest;
import com.dailytracker.api.dto.response.AuthResponse;
import com.dailytracker.api.entity.RefreshToken;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.RefreshTokenRepository;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MessageService messageService;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpiration;

    public Integer register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException(messageService.get("error.email.exists"));
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .language(request.language() != null ? request.language() : "pt-BR")
                .build();

        user = userRepository.save(user);
        return user.getId();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadRequestException("Credenciais inválidas."));

        if (user.getPassword() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadRequestException(messageService.get("error.credentials.invalid"));
        }

        String token = jwtService.generateToken(user.getId());
        RefreshToken refreshToken = createRefreshToken(user);

        return new AuthResponse(token, refreshToken.getToken());
    }

    @Transactional
    public AuthResponse refreshToken(String requestRefreshToken) {
        RefreshToken rt = refreshTokenRepository.findByToken(requestRefreshToken)
                .map(this::verifyExpiration)
                .orElseThrow(() -> new BadRequestException(messageService.get("error.token.refresh.invalid")));

        User user = rt.getUser();
        String token = jwtService.generateToken(user.getId());

        // Update current refresh token expiry
        rt.setExpiryDate(Instant.now().plusMillis(refreshExpiration));
        refreshTokenRepository.save(rt);

        return new AuthResponse(token, requestRefreshToken);
    }

    @Transactional
    public RefreshToken createRefreshToken(User user) {
        refreshTokenRepository.deleteByUser(user); // Single session for simplicity, or remove this for multiple sessions

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(refreshExpiration))
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(token);
            throw new BadRequestException(messageService.get("error.token.refresh.expired"));
        }
        return token;
    }
}
