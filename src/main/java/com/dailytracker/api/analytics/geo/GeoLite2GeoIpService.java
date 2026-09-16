package com.dailytracker.api.analytics.geo;

import com.dailytracker.api.analytics.AnalyticsProperties;
import com.maxmind.geoip2.DatabaseReader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GeoLite2-Country lookup from a local .mmdb file. Degrades gracefully: when the file is missing
 * and MaxMind credentials are not set, every lookup returns empty and the app boots normally.
 * With credentials, the database is downloaded in the background at boot (if missing or older
 * than 30 days) and refreshed weekly. The reader is hot-swapped, never blocking requests.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GeoLite2GeoIpService implements GeoIpService {

    private static final String DOWNLOAD_URL =
            "https://download.maxmind.com/geoip/databases/GeoLite2-Country/download?suffix=tar.gz";
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private final AnalyticsProperties properties;
    private final AtomicReference<DatabaseReader> reader = new AtomicReference<>();

    @PostConstruct
    void init() {
        Path db = dbPath();
        if (Files.exists(db)) {
            load(db);
        } else {
            log.info("GeoIP database not found at {} — country lookups disabled until downloaded", db);
        }
        Thread.ofVirtual().name("geoip-bootstrap").start(this::refreshIfStale);
    }

    @PreDestroy
    void close() {
        DatabaseReader r = reader.getAndSet(null);
        if (r != null) {
            try { r.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public Optional<String> lookupCountry(String ip) {
        DatabaseReader r = reader.get();
        if (r == null || ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                return Optional.empty();
            }
            return r.tryCountry(addr)
                    .map(c -> c.getCountry().getIsoCode())
                    .filter(code -> code != null && !code.isBlank());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Weekly refresh (Mondays 04:00 São Paulo). Boot-time refresh happens in {@link #init()}. */
    @Scheduled(cron = "0 0 4 * * MON", zone = "America/Sao_Paulo")
    public void weeklyRefresh() {
        refreshIfStale();
    }

    private void refreshIfStale() {
        if (!hasCredentials()) {
            return;
        }
        try {
            Path db = dbPath();
            if (Files.exists(db)) {
                FileTime modified = Files.getLastModifiedTime(db);
                if (modified.toInstant().isAfter(Instant.now().minus(MAX_AGE))) {
                    return;
                }
            }
            download(db);
            load(db);
        } catch (Exception e) {
            log.warn("GeoIP database refresh failed: {}", e.getMessage());
        }
    }

    private void download(Path target) throws IOException, InterruptedException {
        AnalyticsProperties.GeoIp cfg = properties.getAnalytics().getGeoip();
        String basic = Base64.getEncoder().encodeToString(
                (cfg.getMaxmindAccountId() + ":" + cfg.getMaxmindLicenseKey()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                .header("Authorization", "Basic " + basic)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("MaxMind responded HTTP " + response.statusCode());
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (InputStream body = response.body();
                 TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(body))) {
                TarArchiveEntry entry;
                boolean found = false;
                while ((entry = tar.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".mmdb")) {
                        Files.copy(tar, tmp, StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IOException("no .mmdb inside MaxMind archive");
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("GeoIP database downloaded to {}", target);
        }
    }

    private void load(Path db) {
        try {
            DatabaseReader fresh = new DatabaseReader.Builder(db.toFile()).build();
            DatabaseReader old = reader.getAndSet(fresh);
            if (old != null) {
                old.close();
            }
            log.info("GeoIP database loaded from {}", db);
        } catch (IOException e) {
            log.warn("GeoIP database at {} could not be loaded: {}", db, e.getMessage());
        }
    }

    private boolean hasCredentials() {
        AnalyticsProperties.GeoIp cfg = properties.getAnalytics().getGeoip();
        return !cfg.getMaxmindAccountId().isBlank() && !cfg.getMaxmindLicenseKey().isBlank();
    }

    private Path dbPath() {
        return Path.of(properties.getAnalytics().getGeoip().getDbPath());
    }
}
