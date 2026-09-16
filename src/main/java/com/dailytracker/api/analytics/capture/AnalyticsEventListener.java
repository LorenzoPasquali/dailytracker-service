package com.dailytracker.api.analytics.capture;

import com.dailytracker.api.analytics.geo.GeoIpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns auth/activity events into rows, off the request thread. Failures are logged, never propagated.
 * Activity is deduped per user per São Paulo day with a small in-memory map (cleared daily).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnalyticsEventListener {

    private final AnalyticsWriteRepository writes;
    private final GeoIpService geoIp;
    private final Map<Integer, LocalDate> lastActivityDay = new ConcurrentHashMap<>();

    /** After commit so the User row is visible (signup); fallbackExecution covers non-transactional callers. */
    @Async("analyticsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAuthenticated(AnalyticsEvents.UserAuthenticated event) {
        try {
            String country = geoIp.lookupCountry(event.ip()).orElse(null);
            writes.insertLoginEvent(event.userId(), event.kind().name().toLowerCase(),
                    event.method().name().toLowerCase(), country, event.at());
            if (event.kind() == AnalyticsEvents.Kind.SIGNUP && country != null) {
                writes.setSignupCountryIfNull(event.userId(), country);
            }
            recordActivity(event.userId(), AnalyticsWriteRepository.dayOf(event.at()), country);
        } catch (Exception e) {
            log.warn("analytics: failed to record auth event for user {}: {}", event.userId(), e.getMessage());
        }
    }

    @Async("analyticsExecutor")
    @EventListener
    public void onUserActivity(AnalyticsEvents.UserActivity event) {
        try {
            LocalDate day = AnalyticsWriteRepository.dayOf(event.at());
            if (day.equals(lastActivityDay.get(event.userId()))) {
                return;
            }
            String country = geoIp.lookupCountry(event.ip()).orElse(null);
            recordActivity(event.userId(), day, country);
        } catch (Exception e) {
            log.warn("analytics: failed to record activity for user {}: {}", event.userId(), e.getMessage());
        }
    }

    private void recordActivity(Integer userId, LocalDate day, String country) {
        writes.upsertActivityDay(userId, day, country);
        lastActivityDay.put(userId, day);
    }

    /** Drop yesterday's dedupe entries so the map stays bounded by "users active today". */
    @Scheduled(cron = "0 5 0 * * *", zone = "America/Sao_Paulo")
    public void clearDedupeCache() {
        lastActivityDay.clear();
    }
}
