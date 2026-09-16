package com.dailytracker.api.analytics.capture;

import com.dailytracker.api.analytics.AnalyticsProperties;
import com.dailytracker.api.analytics.ClientIp;
import com.dailytracker.api.analytics.admin.RateLimiter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Anonymous pageview beacon: {@code POST /public/pageview}. No cookies, no consent needed.
 * Visitor identity = sha256(ip|user-agent|day|salt), so the same visitor is counted once per day
 * and cannot be linked across days. The IP is discarded after hashing and GeoIP lookup.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class PageviewController {

    private final AnalyticsProperties properties;
    private final PageviewIngestService ingest;
    // Per-IP limit can be dodged with spoofed X-Forwarded-For, so a global cap backs it up.
    private final RateLimiter perIp = new RateLimiter(60, Duration.ofMinutes(1));
    private final RateLimiter global = new RateLimiter(1200, Duration.ofMinutes(1));
    private String salt;

    public record PageviewRequest(String path, String referrer, String utmSource, String utmMedium, String utmCampaign) {}

    @PostConstruct
    void init() {
        String configured = properties.getAnalytics().getHashSalt();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            salt = HexFormat.of().formatHex(random);
            log.warn("ANALYTICS_HASH_SALT not set — unique visitor counts reset on restart");
        } else {
            salt = configured;
        }
    }

    @PostMapping("/public/pageview")
    public ResponseEntity<Void> track(@RequestBody PageviewRequest body, HttpServletRequest request) {
        String path = body.path();
        if (path == null || !path.startsWith("/") || path.length() > 500) {
            return ResponseEntity.badRequest().build();
        }
        String ip = ClientIp.of(request);
        if (!perIp.tryAcquire(ip) || !global.tryAcquire("*")) {
            return ResponseEntity.status(429).build();
        }
        Instant now = Instant.now();
        String hash = visitorHash(ip, ClientIp.userAgent(request), AnalyticsWriteRepository.dayOf(now).toString());
        ingest.persist(now, path, trim(body.referrer(), 500), trim(body.utmSource(), 100),
                trim(body.utmMedium(), 100), trim(body.utmCampaign(), 100), ip, hash);
        return ResponseEntity.accepted().build();
    }

    private String visitorHash(String ip, String userAgent, String day) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((ip + "|" + userAgent + "|" + day + "|" + salt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
