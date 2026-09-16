# MCP Server (DailyTracker)

The backend embeds an MCP server that exposes the same AI tool surface used internally by the
Gemini chat. External AI clients (e.g. Claude) can connect to read context and create/edit tasks
**scoped to the authenticated user**.

## Architecture

- One MCP server, one tool set (`DailyTrackerMcpTools`), two clients:
  - **internal** — `GeminiService` bridges to the tools in-process (`GeminiToolBridge`);
  - **external** — MCP clients over Streamable HTTP at `/mcp`.
- The acting user is **never** a tool argument. It is resolved from the credential
  (`McpAuthFilter` → `McpPrincipalContext`) and every tool re-checks workspace membership, so a
  client can only ever touch its own data.

## Transport

- Streamable HTTP at `POST/GET /mcp` (`spring.ai.mcp.server.protocol: STREAMABLE`), with periodic
  keep-alive pings so idle streams survive.

`/mcp` accepts two credential kinds on `Authorization: Bearer`:

1. an **OAuth 2.1 access token** (RSA JWT issued by this app) — the native connector path;
2. a legacy per-user opaque token **`dt_mcp_...`** — the manual / `mcp-remote` fallback.

## Tools

| Tool | Type | Notes |
|------|------|-------|
| `list_workspaces` | read | |
| `list_stages` | read | exact stage names to use elsewhere |
| `get_tasks` | read | filters: date/range/status/project; returns `countByStage` |
| `get_projects` | read | |
| `get_task_types` | read | optional `projectName` |
| `create_task` | write | routed through `TaskService` (fires WebSocket + notifications) |
| `update_task` | write | partial update |
| `move_task` | write | change stage by name |

Write tools require the `mcp:write` scope (OAuth) or a read-write `dt_mcp_` token.

## Connecting via OAuth (native "add custom connector")

This is the recommended path (e.g. claude.ai → Settings → Connectors → Add custom connector). The
connector only needs the URL:

```
https://<api-host>/mcp
```

Flow (all automatic once the URL is pasted):

1. The connector hits `/mcp`, gets `401` with `WWW-Authenticate: Bearer resource_metadata="…"`.
2. It fetches the Protected Resource Metadata (RFC 9728) at
   `/.well-known/oauth-protected-resource`, then the Authorization Server metadata (RFC 8414) at
   `/.well-known/oauth-authorization-server`.
3. It self-registers via anonymous Dynamic Client Registration (RFC 7591) at `/connect/register`
   (public PKCE client).
4. It opens `/oauth2/authorize` in the browser. Because the API has no login session, the request
   is redirected to the SPA consent page (`FRONTEND_URL/oauth/consent`), where the logged-in user
   approves **read** and/or **write**. The SPA mints a one-time ticket and resumes the flow.
5. The Authorization Server issues an authorization code → the connector exchanges it at
   `/oauth2/token` for a JWT access token (claims `userId`, `scope`) + refresh token.
6. The connector calls `/mcp` with the Bearer JWT; tools run scoped to that user.

Users can review and revoke connected apps in the MCP panel (backed by
`GET`/`DELETE /api/oauth/authorizations`).

### Production configuration (required)

| Env | Purpose |
|-----|---------|
| `APP_PUBLIC_URL` | Exact public **https** origin of the API. Used as the OAuth issuer; metadata/issuer validation is strict. |
| `FRONTEND_URL` | Public **https** origin of the SPA. The `/oauth2/authorize` redirect target for consent. |
| `MCP_OAUTH_JWK` | RSA signing key (JWK JSON) for access tokens. **Must be stable across restarts** — otherwise every issued/refresh token breaks on each deploy. Blank generates an ephemeral key (dev only) and logs it. |

Also ensure the reverse proxy forwards `X-Forwarded-Proto`/`Host`
(`server.forward-headers-strategy: framework` is set) so the issuer and redirect URIs come out as
https. Flyway `V15` provisions the Spring Authorization Server tables.

To generate a stable `MCP_OAUTH_JWK`: boot once with it unset, copy the JWK JSON from the
`app.oauth.jwk not set …` warning log, and set it as the env value. **It contains a private key —
keep it in the server `.env` / secrets only, never commit it.**

## Legacy: `dt_mcp_` token + mcp-remote

For clients that cannot do OAuth (e.g. Claude Desktop config), issue a per-user token.

Authenticated with the normal app JWT:

```
POST /api/mcp/tokens
{ "label": "Claude Desktop", "readOnly": false, "expiresInDays": 90 }
```

The response includes the plaintext `token` **once** (only its SHA-256 hash is stored). Manage
tokens with `GET /api/mcp/tokens` and `DELETE /api/mcp/tokens/{id}`.

`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "dailytracker": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "https://<api-host>/mcp",
        "--header", "Authorization: Bearer dt_mcp_XXXXXXXX"
      ]
    }
  }
}
```

Restart Claude Desktop; the DailyTracker tools appear and operate on that user's workspace.
