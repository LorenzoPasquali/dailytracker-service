package com.dailytracker.api.analytics.query;

import com.dailytracker.api.analytics.query.AnalyticsQueryRepository.CohortCell;
import com.dailytracker.api.analytics.query.AnalyticsQueryRepository.CohortSize;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read endpoints for the hidden dashboard. All gated by ROLE_ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private static final int COHORT_WEEKS = 12;
    private static final int COHORT_OFFSETS = 8;

    private final AnalyticsQueryRepository queries;

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DateRange range = DateRange.of(from, to);
        DateRange previous = DateRange.of(range.from().minusDays(range.days()), range.from().minusDays(1));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", Map.of("from", range.from(), "to", range.to()));
        out.put("kpis", kpis(range));
        out.put("previousKpis", kpis(previous));
        out.put("series", queries.dailySeries(range));
        out.put("topPaths", queries.topPaths(range, 10));
        out.put("topReferrers", queries.topReferrers(range, 10));
        out.put("utmSources", queries.utmSources(range, 10));
        out.put("signupsByMethod", queries.signupsByMethod(range));
        out.put("funnel", queries.funnel(range));
        return out;
    }

    private Map<String, Object> kpis(DateRange range) {
        long visitors = queries.uniqueVisitors(range);
        long signups = queries.signups(range);
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("pageviews", queries.pageviews(range));
        k.put("uniqueVisitors", visitors);
        k.put("signups", signups);
        k.put("activeUsers", queries.activeUsers(range));
        k.put("totalUsers", queries.totalUsers());
        k.put("visitorToSignupRate", visitors == 0 ? 0.0 : (double) signups / visitors);
        return k;
    }

    @GetMapping("/engagement")
    public Map<String, Object> engagement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DateRange range = DateRange.of(from, to);
        List<AnalyticsQueryRepository.EngagementPoint> series = queries.engagementSeries(range);
        AnalyticsQueryRepository.EngagementPoint last = series.isEmpty() ? null : series.get(series.size() - 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", Map.of("from", range.from(), "to", range.to()));
        out.put("series", series);
        out.put("current", last == null ? Map.of() : Map.of(
                "dau", last.dau(), "wau", last.wau(), "mau", last.mau(),
                "stickiness", last.mau() == 0 ? 0.0 : (double) last.dau() / last.mau()));
        out.put("cohorts", cohorts());
        return out;
    }

    /** Weekly signup cohorts × weeks-since-signup retention (% of cohort active in that week). */
    private List<Map<String, Object>> cohorts() {
        List<CohortSize> sizes = queries.cohortSizes(COHORT_WEEKS);
        List<CohortCell> cells = queries.cohortRetention(COHORT_WEEKS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CohortSize size : sizes) {
            Long[] retained = new Long[COHORT_OFFSETS + 1];
            for (CohortCell c : cells) {
                if (c.cohortWeek().equals(size.cohortWeek()) && c.weekOffset() >= 0 && c.weekOffset() <= COHORT_OFFSETS) {
                    retained[c.weekOffset()] = c.retained();
                }
            }
            List<Double> pct = new ArrayList<>();
            LocalDate thisWeek = LocalDate.now(com.dailytracker.api.analytics.AnalyticsProperties.ZONE);
            for (int w = 0; w <= COHORT_OFFSETS; w++) {
                boolean inFuture = size.cohortWeek().plusWeeks(w).isAfter(thisWeek);
                pct.add(inFuture ? null : (size.size() == 0 ? 0.0 : (retained[w] == null ? 0L : retained[w]) * 100.0 / size.size()));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cohortWeek", size.cohortWeek());
            row.put("size", size.size());
            row.put("retention", pct);
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/users")
    public List<AnalyticsQueryRepository.TopUser> users(@RequestParam(defaultValue = "50") int limit) {
        return queries.topUsers(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/geo")
    public Map<String, Object> geo(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DateRange range = DateRange.of(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", Map.of("from", range.from(), "to", range.to()));
        out.put("countries", queries.byCountry(range));
        return out;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return queries.health();
    }

}
