package com.dailytracker.api.analytics.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter keyed by an arbitrary string (usually the client IP).
 * Single-instance only; good enough for this deployment. Idle keys are pruned on each call.
 */
public class RateLimiter {

    private final int maxHits;
    private final Duration window;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int maxHits, Duration window) {
        this.maxHits = maxHits;
        this.window = window;
    }

    /** Records a hit and reports whether the caller is still within the limit. */
    public boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxHits) {
                return false;
            }
            deque.addLast(now);
        }
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(cutoff);
                }
            });
        }
        return true;
    }
}
