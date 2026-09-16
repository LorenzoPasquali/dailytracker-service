package com.dailytracker.api.analytics.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read side of the admin dashboard: native SQL over the analytics tables plus "User"/"Task"/"Workspace".
 * Days are bucketed in America/Sao_Paulo (the {@code TZ} constant); range filters use timestamptz bounds
 * from {@link DateRange} so indexes on {@code occurred_at}/{@code "createdAt"} stay usable.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsQueryRepository {

    private static final String TZ = "'America/Sao_Paulo'";
    private static final String DAY_PV = "(occurred_at AT TIME ZONE " + TZ + ")::date";
    private static final String DAY_USER = "(u.\"createdAt\" AT TIME ZONE " + TZ + ")::date";

    private final NamedParameterJdbcTemplate jdbc;

    public record DailyPoint(LocalDate day, long pageviews, long visitors, long signups, long activeUsers) {}
    public record Bucket(String key, long count) {}
    public record EngagementPoint(LocalDate day, long dau, long wau, long mau) {}
    public record CohortCell(LocalDate cohortWeek, int weekOffset, long retained) {}
    public record CohortSize(LocalDate cohortWeek, long size) {}
    public record TopUser(Integer id, String name, String email, Instant createdAt, String country, String method,
                          long tasks, long activeDays30, long activeDaysTotal, LocalDate lastSeen) {}
    public record CountryRow(String country, long pageviews, long visitors, long signups, long activeUsers) {}

    private static MapSqlParameterSource params(DateRange r) {
        return new MapSqlParameterSource()
                .addValue("fromTs", r.fromTs())
                .addValue("toTs", r.toTs())
                .addValue("from", r.from())
                .addValue("to", r.to());
    }

    // ---------- KPIs ----------

    public long pageviews(DateRange r) {
        return count("SELECT count(*) FROM pageview_event WHERE occurred_at >= :fromTs AND occurred_at < :toTs", r);
    }

    public long uniqueVisitors(DateRange r) {
        return count("SELECT count(DISTINCT visitor_hash) FROM pageview_event WHERE occurred_at >= :fromTs AND occurred_at < :toTs", r);
    }

    public long signups(DateRange r) {
        return count("SELECT count(*) FROM \"User\" WHERE \"createdAt\" >= :fromTs AND \"createdAt\" < :toTs", r);
    }

    public long activeUsers(DateRange r) {
        return count("SELECT count(DISTINCT user_id) FROM user_activity_day WHERE day >= :from AND day <= :to", r);
    }

    public long totalUsers() {
        return jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM \"User\"", Long.class);
    }

    private long count(String sql, DateRange r) {
        Long v = jdbc.queryForObject(sql, params(r), Long.class);
        return v == null ? 0 : v;
    }

    // ---------- Series ----------

    public List<DailyPoint> dailySeries(DateRange r) {
        String sql = """
                WITH days AS (
                    SELECT generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day')::date AS day
                ),
                pv AS (
                    SELECT %s AS day, count(*) AS pageviews, count(DISTINCT visitor_hash) AS visitors
                    FROM pageview_event WHERE occurred_at >= :fromTs AND occurred_at < :toTs GROUP BY 1
                ),
                su AS (
                    SELECT %s AS day, count(*) AS signups
                    FROM "User" u WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs GROUP BY 1
                ),
                au AS (
                    SELECT day, count(DISTINCT user_id) AS active_users
                    FROM user_activity_day WHERE day >= :from AND day <= :to GROUP BY 1
                )
                SELECT d.day,
                       COALESCE(pv.pageviews, 0) AS pageviews,
                       COALESCE(pv.visitors, 0) AS visitors,
                       COALESCE(su.signups, 0) AS signups,
                       COALESCE(au.active_users, 0) AS active_users
                FROM days d
                LEFT JOIN pv ON pv.day = d.day
                LEFT JOIN su ON su.day = d.day
                LEFT JOIN au ON au.day = d.day
                ORDER BY d.day
                """.formatted(DAY_PV, DAY_USER);
        return jdbc.query(sql, params(r), (rs, i) -> new DailyPoint(
                rs.getObject("day", LocalDate.class), rs.getLong("pageviews"), rs.getLong("visitors"),
                rs.getLong("signups"), rs.getLong("active_users")));
    }

    public List<Bucket> topPaths(DateRange r, int limit) {
        return buckets("""
                SELECT path AS key, count(*) AS count FROM pageview_event
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                GROUP BY path ORDER BY count DESC LIMIT :limit
                """, r, limit);
    }

    public List<Bucket> topReferrers(DateRange r, int limit) {
        return buckets("""
                SELECT COALESCE(NULLIF(substring(referrer FROM '^(?:https?://)?([^/]+)'), ''), '(direct)') AS key,
                       count(*) AS count
                FROM pageview_event
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                GROUP BY 1 ORDER BY count DESC LIMIT :limit
                """, r, limit);
    }

    public List<Bucket> utmSources(DateRange r, int limit) {
        return buckets("""
                SELECT utm_source AS key, count(*) AS count FROM pageview_event
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs AND utm_source IS NOT NULL
                GROUP BY 1 ORDER BY count DESC LIMIT :limit
                """, r, limit);
    }

    public List<Bucket> signupsByMethod(DateRange r) {
        return buckets("""
                SELECT CASE WHEN u."googleId" IS NOT NULL THEN 'google' ELSE 'email' END AS key, count(*) AS count
                FROM "User" u WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs
                GROUP BY 1 ORDER BY count DESC LIMIT :limit
                """, r, 10);
    }

    private List<Bucket> buckets(String sql, DateRange r, int limit) {
        return jdbc.query(sql, params(r).addValue("limit", limit),
                (rs, i) -> new Bucket(rs.getString("key"), rs.getLong("count")));
    }

    // ---------- Funnel ----------

    public Map<String, Long> funnel(DateRange r) {
        String sql = """
                SELECT
                  (SELECT count(DISTINCT visitor_hash) FROM pageview_event
                     WHERE occurred_at >= :fromTs AND occurred_at < :toTs) AS visitors,
                  (SELECT count(DISTINCT visitor_hash) FROM pageview_event
                     WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                       AND (path = '/register' OR path = '/login')) AS auth_page,
                  (SELECT count(*) FROM "User" u
                     WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs) AS accounts,
                  (SELECT count(*) FROM "User" u
                     WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs
                       AND u."onboardingCompleted" = TRUE) AS onboarded,
                  (SELECT count(*) FROM "User" u
                     WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs
                       AND EXISTS (SELECT 1 FROM "Task" t WHERE t."userId" = u.id)) AS first_task,
                  (SELECT count(*) FROM "User" u
                     WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs
                       AND EXISTS (SELECT 1 FROM user_activity_day a
                                   WHERE a.user_id = u.id
                                     AND a.day >= (u."createdAt" AT TIME ZONE 'America/Sao_Paulo')::date + 7)) AS retained_w1
                """;
        return jdbc.queryForObject(sql, params(r), (rs, i) -> Map.of(
                "visitors", rs.getLong("visitors"),
                "authPage", rs.getLong("auth_page"),
                "accounts", rs.getLong("accounts"),
                "onboarded", rs.getLong("onboarded"),
                "firstTask", rs.getLong("first_task"),
                "retainedWeek1", rs.getLong("retained_w1")));
    }

    // ---------- Engagement ----------

    public List<EngagementPoint> engagementSeries(DateRange r) {
        String sql = """
                SELECT d.day,
                  (SELECT count(DISTINCT user_id) FROM user_activity_day WHERE day = d.day) AS dau,
                  (SELECT count(DISTINCT user_id) FROM user_activity_day WHERE day > d.day - 7 AND day <= d.day) AS wau,
                  (SELECT count(DISTINCT user_id) FROM user_activity_day WHERE day > d.day - 30 AND day <= d.day) AS mau
                FROM (SELECT generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day')::date AS day) d
                ORDER BY d.day
                """;
        return jdbc.query(sql, params(r), (rs, i) -> new EngagementPoint(
                rs.getObject("day", LocalDate.class), rs.getLong("dau"), rs.getLong("wau"), rs.getLong("mau")));
    }

    public List<CohortSize> cohortSizes(int weeks) {
        String sql = """
                SELECT date_trunc('week', u."createdAt" AT TIME ZONE 'America/Sao_Paulo')::date AS cohort_week,
                       count(*) AS size
                FROM "User" u
                WHERE u."createdAt" >= (date_trunc('week', now() AT TIME ZONE 'America/Sao_Paulo') - make_interval(weeks => :weeks))
                GROUP BY 1 ORDER BY 1
                """;
        return jdbc.query(sql, new MapSqlParameterSource("weeks", weeks),
                (rs, i) -> new CohortSize(rs.getObject("cohort_week", LocalDate.class), rs.getLong("size")));
    }

    public List<CohortCell> cohortRetention(int weeks) {
        String sql = """
                WITH cohort AS (
                    SELECT u.id AS user_id,
                           date_trunc('week', u."createdAt" AT TIME ZONE 'America/Sao_Paulo')::date AS cohort_week
                    FROM "User" u
                    WHERE u."createdAt" >= (date_trunc('week', now() AT TIME ZONE 'America/Sao_Paulo') - make_interval(weeks => :weeks))
                )
                SELECT c.cohort_week,
                       ((date_trunc('week', a.day::timestamp)::date - c.cohort_week) / 7) AS week_offset,
                       count(DISTINCT c.user_id) AS retained
                FROM cohort c
                JOIN user_activity_day a ON a.user_id = c.user_id AND a.day >= c.cohort_week
                GROUP BY 1, 2 ORDER BY 1, 2
                """;
        return jdbc.query(sql, new MapSqlParameterSource("weeks", weeks),
                (rs, i) -> new CohortCell(rs.getObject("cohort_week", LocalDate.class),
                        rs.getInt("week_offset"), rs.getLong("retained")));
    }

    // ---------- Users ----------

    public List<TopUser> topUsers(int limit) {
        String sql = """
                SELECT u.id, u.name, u.email, u."createdAt" AS created_at, u."signupCountry" AS country,
                       CASE WHEN u."googleId" IS NOT NULL THEN 'google' ELSE 'email' END AS method,
                       (SELECT count(*) FROM "Task" t WHERE t."userId" = u.id) AS tasks,
                       (SELECT count(*) FROM user_activity_day a WHERE a.user_id = u.id
                          AND a.day > (now() AT TIME ZONE 'America/Sao_Paulo')::date - 30) AS active_days_30,
                       (SELECT count(*) FROM user_activity_day a WHERE a.user_id = u.id) AS active_days_total,
                       (SELECT max(a.day) FROM user_activity_day a WHERE a.user_id = u.id) AS last_seen
                FROM "User" u
                ORDER BY active_days_30 DESC, tasks DESC, active_days_total DESC, u.id
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource("limit", limit), AnalyticsQueryRepository::topUser);
    }

    private static TopUser topUser(ResultSet rs, int i) throws SQLException {
        Instant createdAt = rs.getObject("created_at", java.time.OffsetDateTime.class) != null
                ? rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant() : null;
        return new TopUser(rs.getInt("id"), rs.getString("name"), rs.getString("email"), createdAt,
                rs.getString("country"), rs.getString("method"), rs.getLong("tasks"),
                rs.getLong("active_days_30"), rs.getLong("active_days_total"),
                rs.getObject("last_seen", LocalDate.class));
    }

    // ---------- Geo ----------

    public List<CountryRow> byCountry(DateRange r) {
        String sql = """
                WITH pv AS (
                    SELECT country, count(*) AS pageviews, count(DISTINCT visitor_hash) AS visitors
                    FROM pageview_event
                    WHERE occurred_at >= :fromTs AND occurred_at < :toTs AND country IS NOT NULL GROUP BY 1
                ),
                su AS (
                    SELECT u."signupCountry" AS country, count(*) AS signups
                    FROM "User" u
                    WHERE u."createdAt" >= :fromTs AND u."createdAt" < :toTs AND u."signupCountry" IS NOT NULL GROUP BY 1
                ),
                au AS (
                    SELECT country, count(DISTINCT user_id) AS active_users
                    FROM user_activity_day
                    WHERE day >= :from AND day <= :to AND country IS NOT NULL GROUP BY 1
                ),
                keys AS (
                    SELECT country FROM pv UNION SELECT country FROM su UNION SELECT country FROM au
                )
                SELECT k.country,
                       COALESCE(pv.pageviews, 0) AS pageviews,
                       COALESCE(pv.visitors, 0) AS visitors,
                       COALESCE(su.signups, 0) AS signups,
                       COALESCE(au.active_users, 0) AS active_users
                FROM keys k
                LEFT JOIN pv ON pv.country = k.country
                LEFT JOIN su ON su.country = k.country
                LEFT JOIN au ON au.country = k.country
                ORDER BY visitors DESC, signups DESC, active_users DESC
                """;
        return jdbc.query(sql, params(r), (rs, i) -> new CountryRow(rs.getString("country"),
                rs.getLong("pageviews"), rs.getLong("visitors"), rs.getLong("signups"), rs.getLong("active_users")));
    }

    // ---------- Health ----------

    public Map<String, Object> health() {
        String sql = """
                SELECT
                  (SELECT count(*) FROM "User") AS users,
                  (SELECT count(*) FROM "User" u WHERE u."googleId" IS NOT NULL) AS google_users,
                  (SELECT count(*) FROM "Workspace") AS workspaces,
                  (SELECT count(*) FROM "Workspace" w WHERE w."isPersonal" = FALSE) AS team_workspaces,
                  (SELECT count(*) FROM "Task") AS tasks,
                  (SELECT count(*) FROM "Project") AS projects,
                  (SELECT count(*) FROM "McpAccessToken" m WHERE m.revoked = FALSE) AS mcp_tokens,
                  (SELECT count(DISTINCT m."userId") FROM "McpAccessToken" m WHERE m.revoked = FALSE) AS mcp_users,
                  (SELECT count(*) FROM oauth2_authorization) AS oauth_authorizations,
                  (SELECT count(*) FROM "NotificationSchedule" n WHERE n.status = 'SENT'
                     AND n."sentAt" >= now() - interval '7 days') AS notifications_sent_7d,
                  (SELECT count(*) FROM "NotificationSchedule" n WHERE n.status = 'FAILED'
                     AND n."createdAt" >= now() - interval '7 days') AS notifications_failed_7d,
                  (SELECT count(*) FROM "NotificationSchedule" n WHERE n.status = 'PENDING') AS notifications_pending,
                  (SELECT count(*) FROM pageview_event) AS pageview_rows
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), (rs, i) -> Map.ofEntries(
                Map.entry("users", rs.getLong("users")),
                Map.entry("googleUsers", rs.getLong("google_users")),
                Map.entry("workspaces", rs.getLong("workspaces")),
                Map.entry("teamWorkspaces", rs.getLong("team_workspaces")),
                Map.entry("tasks", rs.getLong("tasks")),
                Map.entry("projects", rs.getLong("projects")),
                Map.entry("mcpTokens", rs.getLong("mcp_tokens")),
                Map.entry("mcpUsers", rs.getLong("mcp_users")),
                Map.entry("oauthAuthorizations", rs.getLong("oauth_authorizations")),
                Map.entry("notificationsSent7d", rs.getLong("notifications_sent_7d")),
                Map.entry("notificationsFailed7d", rs.getLong("notifications_failed_7d")),
                Map.entry("notificationsPending", rs.getLong("notifications_pending")),
                Map.entry("pageviewRows", rs.getLong("pageview_rows"))));
    }
}
