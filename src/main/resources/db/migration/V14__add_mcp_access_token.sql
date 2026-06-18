-- Per-user MCP access tokens for external clients (e.g. Claude Desktop).
-- Only the SHA-256 hash of the token is stored; the plaintext is shown once at creation.
CREATE TABLE "McpAccessToken" (
    id SERIAL PRIMARY KEY,
    "userId" INTEGER NOT NULL REFERENCES "User"(id) ON DELETE CASCADE,
    "tokenHash" TEXT NOT NULL UNIQUE,
    label TEXT,
    "readOnly" BOOLEAN NOT NULL DEFAULT FALSE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "lastUsedAt" TIMESTAMPTZ,
    "expiresAt" TIMESTAMPTZ
);

CREATE INDEX idx_mcp_token_user ON "McpAccessToken"("userId");
