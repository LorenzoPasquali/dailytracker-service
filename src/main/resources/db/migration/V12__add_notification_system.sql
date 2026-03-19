CREATE TABLE public."NotificationRule" (
    id              SERIAL PRIMARY KEY,
    "workspaceId"   INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    "projectId"     INTEGER          REFERENCES public."Project"(id)   ON DELETE CASCADE,
    "createdById"   INTEGER NOT NULL REFERENCES public."User"(id)      ON DELETE CASCADE,
    "name"          VARCHAR(120) NOT NULL,
    "isActive"      BOOLEAN NOT NULL DEFAULT TRUE,
    "createdAt"     TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt"     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_rule_workspace ON public."NotificationRule"("workspaceId");
CREATE INDEX idx_notification_rule_project   ON public."NotificationRule"("projectId");

CREATE TABLE public."NotificationRecipient" (
    id        SERIAL PRIMARY KEY,
    "ruleId"  INTEGER NOT NULL REFERENCES public."NotificationRule"(id) ON DELETE CASCADE,
    email     VARCHAR(255) NOT NULL,
    UNIQUE ("ruleId", email)
);

CREATE TABLE public."NotificationOffset" (
    id        SERIAL PRIMARY KEY,
    "ruleId"  INTEGER NOT NULL REFERENCES public."NotificationRule"(id) ON DELETE CASCADE,
    minutes   INTEGER NOT NULL CHECK (minutes >= 0),
    UNIQUE ("ruleId", minutes)
);

CREATE TABLE public."NotificationSchedule" (
    id               SERIAL PRIMARY KEY,
    "taskId"         INTEGER NOT NULL REFERENCES public."Task"(id)              ON DELETE CASCADE,
    "ruleId"         INTEGER NOT NULL REFERENCES public."NotificationRule"(id)  ON DELETE CASCADE,
    "recipientEmail" VARCHAR(255) NOT NULL,
    "offsetMinutes"  INTEGER NOT NULL,
    "scheduledAt"    TIMESTAMPTZ NOT NULL,
    "status"         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    "sentAt"         TIMESTAMPTZ,
    "retryCount"     INTEGER NOT NULL DEFAULT 0,
    "nextRetryAt"    TIMESTAMPTZ,
    "errorMessage"   VARCHAR(500),
    "createdAt"      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("taskId", "ruleId", "recipientEmail", "offsetMinutes")
);

CREATE INDEX idx_notification_schedule_pending
    ON public."NotificationSchedule"("scheduledAt")
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_schedule_retry
    ON public."NotificationSchedule"("nextRetryAt")
    WHERE status = 'PENDING' AND "retryCount" > 0;
