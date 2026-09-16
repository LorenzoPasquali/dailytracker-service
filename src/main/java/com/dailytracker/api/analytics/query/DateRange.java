package com.dailytracker.api.analytics.query;

import com.dailytracker.api.analytics.AnalyticsProperties;

import java.sql.Timestamp;
import java.time.LocalDate;

/** Inclusive calendar-day range in America/Sao_Paulo, with the timestamptz bounds used for index-friendly filters. */
public record DateRange(LocalDate from, LocalDate to) {

    public static DateRange of(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(AnalyticsProperties.ZONE);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            start = end;
        }
        if (end.minusDays(366).isAfter(start)) {
            start = end.minusDays(366);
        }
        return new DateRange(start, end);
    }

    public Timestamp fromTs() {
        return Timestamp.from(from.atStartOfDay(AnalyticsProperties.ZONE).toInstant());
    }

    /** Exclusive upper bound: start of the day after {@code to}. */
    public Timestamp toTs() {
        return Timestamp.from(to.plusDays(1).atStartOfDay(AnalyticsProperties.ZONE).toInstant());
    }

    public long days() {
        return to.toEpochDay() - from.toEpochDay() + 1;
    }
}
