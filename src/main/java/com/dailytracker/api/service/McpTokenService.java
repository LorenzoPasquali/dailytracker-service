package com.dailytracker.api.service;

import com.dailytracker.api.entity.McpAccessToken;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.McpAccessTokenRepository;
import com.dailytracker.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and validates per-user MCP access tokens. The plaintext token is shown exactly once at
 * creation; only its SHA-256 hash is persisted, so a database leak cannot expose usable tokens.
 */
@Service
@RequiredArgsConstructor
public class McpTokenService {

    private static final String TOKEN_PREFIX = "dt_mcp_";
    private static final int TOKEN_BYTES = 32;

    private final McpAccessTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Resolved identity of a valid token, handed to the MCP auth filter. */
    public record ResolvedToken(Integer userId, boolean readOnly) {}

    @Transactional
    public Map<String, Object> create(Integer userId, String label, boolean readOnly, Integer expiresInDays) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        String raw = TOKEN_PREFIX + randomToken();
        Instant expiresAt = (expiresInDays != null && expiresInDays > 0)
                ? Instant.now().plus(expiresInDays, ChronoUnit.DAYS)
                : null;

        McpAccessToken token = McpAccessToken.builder()
                .user(user)
                .tokenHash(sha256(raw))
                .label(label != null && !label.isBlank() ? label.trim() : "MCP token")
                .readOnly(readOnly)
                .revoked(false)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
        token = tokenRepository.save(token);

        Map<String, Object> result = toResponse(token);
        // Plaintext returned only here, never persisted or shown again.
        result.put("token", raw);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Integer userId) {
        return tokenRepository.findByUser_IdAndRevokedFalseOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public void revoke(Integer userId, Integer tokenId) {
        McpAccessToken token = tokenRepository.findByIdAndUser_Id(tokenId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.mcp.token.not_found")));
        token.setRevoked(true);
        tokenRepository.save(token);
    }

    /**
     * Validates a presented token and stamps its last-used time. Returns empty for any
     * unknown, revoked, or expired token (and for anything not shaped like an MCP token).
     */
    @Transactional
    public Optional<ResolvedToken> validateAndTouch(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<McpAccessToken> opt = tokenRepository.findByTokenHash(sha256(rawToken));
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        McpAccessToken token = opt.get();
        if (Boolean.TRUE.equals(token.getRevoked())) {
            return Optional.empty();
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        token.setLastUsedAt(Instant.now());
        tokenRepository.save(token);
        return Optional.of(new ResolvedToken(token.getUser().getId(), Boolean.TRUE.equals(token.getReadOnly())));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, Object> toResponse(McpAccessToken token) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", token.getId());
        map.put("label", token.getLabel());
        map.put("readOnly", Boolean.TRUE.equals(token.getReadOnly()));
        map.put("createdAt", token.getCreatedAt().toString());
        map.put("lastUsedAt", token.getLastUsedAt() != null ? token.getLastUsedAt().toString() : null);
        map.put("expiresAt", token.getExpiresAt() != null ? token.getExpiresAt().toString() : null);
        return map;
    }
}
