ALTER TABLE "User" ALTER COLUMN "name" TYPE VARCHAR(20);

-- Truncate any existing names that exceed the new limit
UPDATE "User" SET "name" = LEFT("name", 20) WHERE LENGTH("name") > 20;
