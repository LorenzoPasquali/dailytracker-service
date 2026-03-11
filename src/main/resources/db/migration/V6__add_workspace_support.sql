-- ============================================================
-- V6: Workspace support
-- ============================================================

-- 1. Create Workspace table
CREATE TABLE IF NOT EXISTS public."Workspace" (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    "creatorId"  INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    "isPersonal" BOOLEAN NOT NULL DEFAULT FALSE,
    "createdAt"  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. Create WorkspaceMember table
CREATE TABLE IF NOT EXISTS public."WorkspaceMember" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    "userId"      INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    "joinedAt"    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("workspaceId", "userId")
);

-- 3. Create WorkspaceInvite table
CREATE TABLE IF NOT EXISTS public."WorkspaceInvite" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    token         VARCHAR(64) NOT NULL UNIQUE,
    "expiresAt"   TIMESTAMPTZ NOT NULL,
    "createdAt"   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. Add workspaceId columns (nullable first for backfill)
ALTER TABLE public."Task"    ADD COLUMN IF NOT EXISTS "workspaceId" INTEGER REFERENCES public."Workspace"(id) ON DELETE CASCADE;
ALTER TABLE public."Project" ADD COLUMN IF NOT EXISTS "workspaceId" INTEGER REFERENCES public."Workspace"(id) ON DELETE CASCADE;

-- 5. Backfill: create a personal workspace for each existing user
--    and assign all their tasks/projects to it
DO $$
DECLARE
    u RECORD;
    ws_id INTEGER;
BEGIN
    FOR u IN SELECT id FROM public."User" LOOP
        -- Create personal workspace
        INSERT INTO public."Workspace" (name, "creatorId", "isPersonal", "createdAt")
        VALUES ('Personal', u.id, TRUE, now())
        RETURNING id INTO ws_id;

        -- Add user as CREATOR member
        INSERT INTO public."WorkspaceMember" ("workspaceId", "userId", role, "joinedAt")
        VALUES (ws_id, u.id, 'CREATOR', now());

        -- Assign existing tasks to personal workspace
        UPDATE public."Task"
        SET "workspaceId" = ws_id
        WHERE "userId" = u.id;

        -- Assign existing projects to personal workspace
        UPDATE public."Project"
        SET "workspaceId" = ws_id
        WHERE "userId" = u.id;
    END LOOP;
END $$;

-- 6. Add NOT NULL constraint after backfill
ALTER TABLE public."Task"    ALTER COLUMN "workspaceId" SET NOT NULL;
ALTER TABLE public."Project" ALTER COLUMN "workspaceId" SET NOT NULL;

-- 7. Indexes
CREATE INDEX IF NOT EXISTS idx_workspace_member_user      ON public."WorkspaceMember"("userId");
CREATE INDEX IF NOT EXISTS idx_workspace_member_workspace ON public."WorkspaceMember"("workspaceId");
CREATE INDEX IF NOT EXISTS idx_task_workspace             ON public."Task"("workspaceId");
CREATE INDEX IF NOT EXISTS idx_project_workspace          ON public."Project"("workspaceId");
