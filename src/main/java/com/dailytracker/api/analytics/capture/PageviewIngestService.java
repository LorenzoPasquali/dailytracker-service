package com.dailytracker.api.analytics.capture;

import com.dailytracker.api.analytics.geo.GeoIpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Persists pageviews off the request thread. Separate bean so {@code @Async} goes through the proxy. */
@Service
@Slf4j
@RequiredArgsConstructor
public class PageviewIngestService {

    private final AnalyticsWriteRepository writes;
    private final GeoIpService geoIp;

    @Async("analyticsExecutor")
    public void persist(Instant at, String path, String referrer, String utmSource, String utmMedium,
                        String utmCampaign, String ip, String visitorHash) {
        try {
            String country = geoIp.lookupCountry(ip).orElse(null);
            writes.insertPageview(at, path, referrer, utmSource, utmMedium, utmCampaign, country, visitorHash);
        } catch (Exception e) {
            log.warn("analytics: failed to record pageview: {}", e.getMessage());
        }
    }
}
