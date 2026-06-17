-- ============================================================
-- V13: Dynamic Kanban stages (per workspace)
-- Replaces the fixed PLANNED/DOING/DONE status enum with
-- user-defined, reorderable stages scoped to a workspace.
-- ============================================================

-- 1. Stage table (workspace-scoped)
CREATE TABLE public."Stage" (
    id            SERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    color         VARCHAR(20)  NOT NULL DEFAULT '#6b7280',
    position      INTEGER      NOT NULL DEFAULT 0,
    "isFinal"     BOOLEAN      NOT NULL DEFAULT FALSE,
    "workspaceId" INTEGER      NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE
);

CREATE INDEX idx_stage_workspace ON public."Stage"("workspaceId");

-- 2. Seed 3 default stages for every existing workspace
DO $$
DECLARE
    w RECORD;
BEGIN
    FOR w IN SELECT id FROM public."Workspace" LOOP
        INSERT INTO public."Stage" (name, color, position, "isFinal", "workspaceId") VALUES
            ('Planejado',    '#a1a1aa', 0, FALSE, w.id),
            ('Em Progresso', '#f59e0b', 1, FALSE, w.id),
            ('Feito',        '#10b981', 2, TRUE,  w.id);
    END LOOP;
END $$;

-- 3. Add Task.stageId
ALTER TABLE public."Task" ADD COLUMN "stageId" INTEGER REFERENCES public."Stage"(id) ON DELETE SET NULL;

-- 4. Backfill Task.stageId from the legacy status string, per workspace
--    PLANNED -> position 0, DOING -> position 1, DONE -> position 2.
--    Anything unmatched falls back to the first stage (position 0).
UPDATE public."Task" t
SET "stageId" = s.id
FROM public."Stage" s
WHERE s."workspaceId" = t."workspaceId"
  AND s.position = CASE t.status
      WHEN 'PLANNED' THEN 0
      WHEN 'DOING'   THEN 1
      WHEN 'DONE'    THEN 2
      ELSE 0
  END;

-- 5. Keep status in sync with the stage name (status becomes a denormalized label)
UPDATE public."Task" t
SET status = s.name
FROM public."Stage" s
WHERE s.id = t."stageId";

-- 6. status is now legacy/denormalized, no longer the source of truth
ALTER TABLE public."Task" ALTER COLUMN status DROP NOT NULL;

CREATE INDEX idx_task_stage ON public."Task"("stageId");
