package com.dailytracker.api.analytics.capture;

import java.time.Instant;

/** Events published by auth code; consumed asynchronously by {@link AnalyticsEventListener}. */
public final class AnalyticsEvents {

    private AnalyticsEvents() {}

    public enum Kind { SIGNUP, LOGIN }
    public enum Method { EMAIL, GOOGLE }

    /** A user signed up or logged in. {@code ip} is used for GeoIP only and never persisted. */
    public record UserAuthenticated(Integer userId, Kind kind, Method method, String ip, Instant at) {}

    /** An authenticated API request happened (once per request, deduped per user/day downstream). */
    public record UserActivity(Integer userId, String ip, Instant at) {}
}
