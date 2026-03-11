ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "onboardingCompleted" BOOLEAN NOT NULL DEFAULT TRUE;

-- Novos usuários criados após esta migration devem ter FALSE
-- O default TRUE garante que usuários existentes não vejam o tutorial
