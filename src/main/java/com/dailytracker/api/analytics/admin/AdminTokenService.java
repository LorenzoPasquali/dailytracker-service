package com.dailytracker.api.analytics.admin;

import com.dailytracker.api.analytics.AnalyticsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless admin session tokens: {@code base64url("admin|issuedAtEpochSeconds") + "." + base64url(HMAC-SHA256)}.
 * Independent from the user JWT (different secret, different shape), so neither can impersonate the other.
 * Password check is constant-time. When no password is configured the dashboard is disabled.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminTokenService {

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final AnalyticsProperties properties;
    private byte[] secret;

    @PostConstruct
    void init() {
        String configured = properties.getAdmin().getTokenSecret();
        if (configured == null || configured.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            log.warn("ADMIN_TOKEN_SECRET not set — admin sessions will not survive a restart");
        } else {
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        if (properties.getAdmin().getPassword().isBlank()) {
            log.warn("ADMIN_PASSWORD not set — /analytics dashboard login is disabled");
        }
    }

    public boolean isEnabled() {
        return !properties.getAdmin().getPassword().isBlank();
    }

    public boolean passwordMatches(String candidate) {
        if (!isEnabled() || candidate == null) {
            return false;
        }
        byte[] a = properties.getAdmin().getPassword().getBytes(StandardCharsets.UTF_8);
        byte[] b = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public record Issued(String token, Instant expiresAt) {}

    public Issued issue() {
        Instant now = Instant.now();
        String payload = "admin|" + now.getEpochSecond();
        String token = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(sign(payload));
        return new Issued(token, now.plus(ttl()));
    }

    /** Returns the expiry when the token is authentic and not expired. */
    public Optional<Instant> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] sig = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), sig)) {
                return Optional.empty();
            }
            String[] parts = payload.split("\\|");
            if (parts.length != 2 || !"admin".equals(parts[0])) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[1])).plus(ttl());
            return expiresAt.isAfter(Instant.now()) ? Optional.of(expiresAt) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Duration ttl() {
        return Duration.ofMinutes(properties.getAdmin().getTokenTtlMinutes());
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
