ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "name" VARCHAR(100) NOT NULL DEFAULT '';

-- Backfill: use the part of the email before '@'
UPDATE "User" SET "name" = SPLIT_PART(email, '@', 1) WHERE "name" = '';

ALTER TABLE "User" ALTER COLUMN "name" DROP DEFAULT;
