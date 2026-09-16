package com.dailytracker.api.analytics.capture;

import com.dailytracker.api.analytics.AnalyticsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/** Insert-only access to the analytics tables (plain JDBC; no JPA entities needed). */
@Repository
@RequiredArgsConstructor
public class AnalyticsWriteRepository {

    private final JdbcTemplate jdbc;

    public void insertPageview(Instant at, String path, String referrer, String utmSource,
                               String utmMedium, String utmCampaign, String country, String visitorHash) {
        jdbc.update("""
                INSERT INTO pageview_event
                    (occurred_at, path, referrer, utm_source, utm_medium, utm_campaign, country, visitor_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Timestamp.from(at), path, referrer, utmSource, utmMedium, utmCampaign, country, visitorHash);
    }

    public void insertLoginEvent(Integer userId, String kind, String method, String country, Instant at) {
        jdbc.update("""
                INSERT INTO login_event (user_id, kind, method, country, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, kind, method, country, Timestamp.from(at));
    }

    /** Idempotent: PK (user_id, day) is the correctness guarantee; the in-memory cache only saves round trips. */
    public void upsertActivityDay(Integer userId, LocalDate day, String country) {
        jdbc.update("""
                INSERT INTO user_activity_day (user_id, day, country)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, day)
                DO UPDATE SET country = COALESCE(user_activity_day.country, EXCLUDED.country)
                """, userId, day, country);
    }

    public void setSignupCountryIfNull(Integer userId, String country) {
        jdbc.update("""
                UPDATE "User" SET "signupCountry" = ? WHERE id = ? AND "signupCountry" IS NULL
                """, country, userId);
    }

    public static LocalDate dayOf(Instant at) {
        return at.atZone(AnalyticsProperties.ZONE).toLocalDate();
    }
}
