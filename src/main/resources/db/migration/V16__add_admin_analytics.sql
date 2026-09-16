-- ============================================================
-- V16: Admin analytics
--   - User.createdAt / User.signupCountry (backfilled from personal workspace)
--   - pageview_event      raw landing/site pageviews (anonymous, no IP stored)
--   - login_event         login/signup events per user
--   - user_activity_day   one row per user per active day (DAU/WAU/MAU/retention)
-- New analytics tables use unquoted snake_case (same as the oauth2_* tables in V15).
-- ============================================================

ALTER TABLE public."User" ADD COLUMN IF NOT EXISTS "createdAt" TIMESTAMPTZ;
ALTER TABLE public."User" ADD COLUMN IF NOT EXISTS "signupCountry" VARCHAR(2);

UPDATE public."User" u
SET "createdAt" = w."createdAt"
FROM public."Workspace" w
WHERE w."creatorId" = u.id AND w."isPersonal" = TRUE AND u."createdAt" IS NULL;

UPDATE public."User" SET "createdAt" = now() WHERE "createdAt" IS NULL;

ALTER TABLE public."User" ALTER COLUMN "createdAt" SET NOT NULL;
ALTER TABLE public."User" ALTER COLUMN "createdAt" SET DEFAULT now();

CREATE TABLE public.pageview_event (
    id            BIGSERIAL PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    path          VARCHAR(500) NOT NULL,
    referrer      VARCHAR(500),
    utm_source    VARCHAR(100),
    utm_medium    VARCHAR(100),
    utm_campaign  VARCHAR(100),
    country       VARCHAR(2),
    visitor_hash  VARCHAR(64) NOT NULL   -- sha256(ip|ua|day|salt); IP itself is never stored
);
CREATE INDEX idx_pageview_event_occurred_at ON public.pageview_event (occurred_at);
CREATE INDEX idx_pageview_event_path        ON public.pageview_event (path);

CREATE TABLE public.login_event (
    id           BIGSERIAL PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    kind         VARCHAR(10) NOT NULL CHECK (kind IN ('signup', 'login')),
    method       VARCHAR(10) NOT NULL CHECK (method IN ('email', 'google')),
    country      VARCHAR(2),
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_event_user_id     ON public.login_event (user_id);
CREATE INDEX idx_login_event_occurred_at ON public.login_event (occurred_at);

CREATE TABLE public.user_activity_day (
    user_id  INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    day      DATE NOT NULL,            -- calendar day in America/Sao_Paulo
    country  VARCHAR(2),
    PRIMARY KEY (user_id, day)
);
CREATE INDEX idx_user_activity_day_day ON public.user_activity_day (day);
