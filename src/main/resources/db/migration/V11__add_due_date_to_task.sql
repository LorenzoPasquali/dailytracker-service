ALTER TABLE public."Task"
    ADD COLUMN "dueDate" TIMESTAMPTZ;

CREATE INDEX idx_task_due_date ON public."Task"("dueDate")
    WHERE "dueDate" IS NOT NULL;
