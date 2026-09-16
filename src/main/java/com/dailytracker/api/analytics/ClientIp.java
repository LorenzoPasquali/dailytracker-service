package com.dailytracker.api.analytics;

import jakarta.servlet.http.HttpServletRequest;

/** Resolves the client IP. {@code server.forward-headers-strategy=framework} already unwraps X-Forwarded-For. */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "" : ip;
    }

    public static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua == null ? "" : ua;
    }
}
