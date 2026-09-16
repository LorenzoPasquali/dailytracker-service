package com.dailytracker.api.analytics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/** Config for the hidden admin analytics dashboard ({@code app.admin.*} and {@code app.analytics.*}). */
@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AnalyticsProperties {

    /** Calendar days for every metric are bucketed in this zone. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private Admin admin = new Admin();
    private Analytics analytics = new Analytics();

    @Getter
    @Setter
    public static class Admin {
        private String password = "";
        private String tokenSecret = "";
        private int tokenTtlMinutes = 120;
    }

    @Getter
    @Setter
    public static class Analytics {
        private String hashSalt = "";
        private GeoIp geoip = new GeoIp();
    }

    @Getter
    @Setter
    public static class GeoIp {
        private String dbPath = "./data/GeoLite2-Country.mmdb";
        private String maxmindAccountId = "";
        private String maxmindLicenseKey = "";
    }
}
