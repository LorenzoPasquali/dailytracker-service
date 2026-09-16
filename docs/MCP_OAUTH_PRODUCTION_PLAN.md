# MCP OAuth 2.1 — Production Readiness Plan

**Goal:** In production, connect Claude (claude.ai native "add custom connector") to this
API's MCP server by pasting only the `/mcp` URL. Claude runs the OAuth 2.1 browser flow
(discovery + DCR + PKCE + consent), obtains a per-user access token, and can then **read
tasks and create/edit tasks** scoped to the authorizing user.

**Chosen route (confirmed by owner):** OAuth native web connector + full backend & frontend,
end-to-end. Legacy `dt_mcp_` token path stays as fallback (Claude Desktop / mcp-remote).

**Status:** IMPLEMENTED (2026-07-14, branch `feature/mcp-oauth`) — backend compiles (`mvnw compile`
EXIT 0), frontend builds (`npm run build` EXIT 0). **Not yet boot/e2e-tested** (needs DB + secrets +
real MCP Inspector/Claude). See "Implementation status" below.

## Implementation status (2026-07-14)

Done, per phase:
- **Fase B** — `McpAuthFilter` now dual-token: `dt_mcp_` via `McpTokenService`, else decode OAuth JWT
  (`JwtDecoder`) → `userId` + `scope` → `readOnly`.
- **Fase D** — `OAuthConsentTicketService` (in-memory one-time tickets),
  `ConsentRedirectAuthenticationEntryPoint` (→ `FRONTEND_URL/oauth/consent`), `ConsentTicketFilter`
  (ticket → `SecurityContext`), `OAuth2ConsentController` (`GET /api/oauth/consent-info`,
  `POST /api/oauth/consent` mints ticket + resume URL with scope narrowed to approved).
  Wired into `AuthorizationServerConfig` chain.
- **Fase E** — `ClientRegistrationController` (`POST /connect/register`, anonymous RFC 7591, https/
  loopback redirect URIs only, public PKCE client) + AS metadata customizer advertising
  `registration_endpoint`.
- **Fase F** — `OAuthAuthorizationController` (`GET`/`DELETE /api/oauth/authorizations`).
- **Frontend** (`dailytracker-app`) — `pages/OAuthConsent.jsx` + `/oauth/consent` route in
  `App.jsx`; `LoginPage` honours `?next=` for post-login return; `McpModal.jsx` restructured
  (OAuth connect primary + authorized-apps list; `dt_mcp_` demoted to collapsible legacy; connect
  URL fixed `/mcp/sse` → `/mcp`); i18n `mcp.*` + new `oauthConsent.*` in pt-BR/en-US/es.
- **Docs** — `docs/mcp-server.md` rewritten (OAuth flow + prod config + `dt_mcp_` fallback).

Still TODO (validation + config, not code):
1. Set prod env `MCP_OAUTH_JWK` (stable), `APP_PUBLIC_URL` (https), `FRONTEND_URL` (https).
2. Boot + e2e with MCP Inspector, then a real claude.ai custom connector — validate the open risks
   in §5 (DCR anonymity, ticket→SecurityContext, scope rewrite, streamable handshake, issuer behind
   proxy, CORS if browser-origin).
3. Commit the branch.

---

**Original plan below (spec).**

---

## 1. Target end-to-end flow (what must work in prod)

```
Claude (claude.ai)                        This API (https://api.<domain>)          SPA (https://app.<domain>)
  │ 1. GET /mcp  (no token)  ───────────────▶ 401 + WWW-Authenticate:
  │                                             Bearer resource_metadata="…/.well-known/oauth-protected-resource"
  │ 2. GET /.well-known/oauth-protected-resource ▶ { authorization_servers:[https://api.<domain>], scopes:[mcp:read,mcp:write] }
  │ 3. GET /.well-known/oauth-authorization-server ▶ { issuer, authorization_endpoint, token_endpoint,
  │                                                    registration_endpoint, … }   (served by SAS)
  │ 4. POST /connect/register (RFC 7591, anonymous) ▶ { client_id, … }   ← DCR (Fase E)
  │ 5. browser → GET /oauth2/authorize?…PKCE…    ─── no session on api ──▶ 302 to SPA /oauth/consent?…   (Fase D entrypoint)
  │                                                                          user (logged via JWT) approves read/write
  │                                             ◀── POST /api/oauth/consent (JWT) mints one-time ticket, returns resumeUrl
  │ browser → GET /oauth2/authorize?…&_ticket=…  ── ticket filter sets SecurityContext=userId ──▶ 302 redirect_uri?code&state
  │ 6. POST /oauth2/token (code+PKCE verifier)   ─────────────────────────▶ JWT access token (claim userId, scope) + refresh
  │ 7. GET/POST /mcp  Bearer <JWT>               ── McpAuthFilter decodes JWT ──▶ tools scoped to userId (Fase B)
```

---

## 2. Current state (verified in code, 2026-07-14)

**Works today**
- MCP server at `/mcp`, Streamable HTTP (`application.yaml` `spring.ai.mcp.server.protocol: STREAMABLE`,
  `streamable-http.mcp-endpoint: /mcp`, keep-alive 20s, request-timeout 60s).
- 8 tools (`mcp/tool/DailyTrackerMcpTools.java`): read (list_workspaces, list_stages, get_tasks
  +countByStage, get_projects, get_task_types) + write (create_task, update_task, move_task).
  Write blocked when `McpPrincipalContext.isReadOnly()`.
- Per-user scoping: userId never a tool arg; resolved from credential → `McpPrincipalContext`
  (ThreadLocal); every tool re-checks workspace membership.
- Legacy `dt_mcp_` path: `mcp/McpAuthFilter.java` validates `Authorization: Bearer dt_mcp_…`
  via `McpTokenService.validateAndTouch` → `ResolvedToken(userId, readOnly)`. Panel `McpModal.jsx`
  (app repo) creates/lists/revokes tokens. Tested on Claude Desktop via mcp-remote.

**OAuth scaffold present (committed `e5cd71c` + uncommitted WIP)**
- `config/AuthorizationServerConfig.java` — SAS `SecurityFilterChain` @Order(1) claiming the AS
  endpoints; `.oidc(withDefaults())`; JDBC repos (`JdbcRegisteredClientRepository`,
  `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService`); `CommandLineRunner`
  seeds public PKCE client `dailytracker-mcp`; `OAuth2TokenCustomizer` adds `userId` claim
  (= principal name); `AuthorizationServerSettings.issuer(app.public-url)`; RSA `JWKSource` from
  `app.oauth.jwk` (or ephemeral + WARN); `JwtDecoder` bean.
- `controller/ProtectedResourceMetadataController.java` — RFC 9728 PRM at
  `/.well-known/oauth-protected-resource[/mcp]`, permitAll.
- `McpAuthFilter` — adds `WWW-Authenticate: Bearer resource_metadata="…"` on 401.
- `db/migration/V15__add_oauth_server.sql` — SAS schema (oauth2_registered_client,
  oauth2_authorization, oauth2_authorization_consent) adapted to Postgres.
- `SecurityConfig.java` — default chain; `/oauth2/**` + `/mcp/**` permitAll; `mcpAuthFilter`
  added before `UsernamePasswordAuthenticationFilter`. **No @Order** → runs after the SAS chain.
- yaml: `app.public-url` (issuer), `app.oauth.jwk` (`MCP_OAUTH_JWK`), `server.forward-headers-strategy: framework`.

**Config facts**
- `CorsConfig.java`: allowed origins = `app.frontend-url` + `http://localhost:5173`,
  allowedHeaders `*`, `allowCredentials true`, applied to `/**`.
- App user auth = JWT (JJWT **HMAC**, claim `userId`). SAS OAuth tokens = **RSA** (different decoder;
  HMAC user tokens will not validate against SAS `JwtDecoder`, so they cannot pass the /mcp OAuth path — expected).
- SPA (`dailytracker-app`, branch `master`) = React Router; routes in `src/App.jsx`; api base
  `src/services/api.js` (`VITE_API_URL`). `McpModal.jsx` exists (11.7 KB).

---

## 3. What is MISSING (the actual work)

### Backend (`/git/dailytracker-service`)

**Fase B — dual-token in `McpAuthFilter`** (small, do first; unblocks testing OAuth tokens)
- Inject the SAS `JwtDecoder`.
- Logic: `if token startsWith "dt_mcp_"` → existing path. `else if token != null` → try
  `jwtDecoder.decode(token)`; read `userId` (String claim → Integer) and `scope`
  (`jwt.getClaimAsStringList("scope")`); `readOnly = !scopes.contains("mcp:write")`;
  `McpPrincipalContext.set(userId, personalWs, readOnly)`. On decode failure or no token → keep
  the existing 401 + WWW-Authenticate.
- Reuse `WorkspaceService.getPersonalWorkspaceId(userId)`.
- Note: `McpTokenService.ResolvedToken` is `(Integer userId, boolean readOnly)`.

**Fase D — resource-owner consent bridge** (the hard part; SPA + JWT, no session on api origin)
1. **Entrypoint** in the SAS chain: replace `LoginUrlAuthenticationEntryPoint("/login")` with a
   custom `AuthenticationEntryPoint` that 302-redirects to
   `${app.frontend-url}/oauth/consent?<full original /oauth2/authorize query string>` (preserve
   response_type, client_id, redirect_uri, scope, state, code_challenge, code_challenge_method,
   nonce, etc., URL-encoded).
2. **`POST /api/oauth/consent`** (JWT-protected, default chain): body = original oauth params +
   `approvedScopes` (subset of {mcp:read, mcp:write}). Validate userId from JWT; validate
   `client_id` exists and `redirect_uri` is registered for it (defense). Mint a **one-time ticket**
   (opaque random) → in-memory map `{ticket → (userId, approvedScopes, expiresAt=now+60s)}`
   (single-instance SSH deploy → in-memory OK; restart drops pending tickets, 60 s TTL, low risk).
   Return `{ resumeUrl: "${app.public-url}/oauth2/authorize?<original params, scope REPLACED by
   approvedScopes>&_ticket=<ticket>" }`.
3. **Ticket filter** in the SAS chain, before `OAuth2AuthorizationEndpointFilter`: on
   `/oauth2/authorize` with `_ticket`, validate + consume the ticket (one-time), build an
   authenticated `Authentication` with `principal name = userId` and set `SecurityContextHolder`.
   SAS then proceeds; because client has `requireAuthorizationConsent(false)`, no SAS consent page,
   and it issues a code for exactly the (rewritten) requested scopes.
4. **Scope = consent:** the rewrite in step 2 (scope param = approvedScopes) IS the consent
   decision. Unchecking write ⇒ `scope=mcp:read` ⇒ token has only mcp:read ⇒ McpAuthFilter sets
   readOnly=true ⇒ write tools return the read-only error.
5. `tokenCustomizer` already stamps `userId = principal.getName()`. Refresh flow reuses the stored
   `oauth2_authorization.principal_name` ⇒ refreshed tokens keep the same userId. ✔

**Fase E — Dynamic Client Registration (RFC 7591), anonymous** (required: claude.ai web has no
client-id field, so it registers dynamically)
- SAS OIDC registration endpoint is disabled by default AND, when enabled, requires an initial
  access token — MCP clients register anonymously. Two implementation options; validate & pick:
  - **(A, recommended)** Custom `POST /connect/register` controller, permitAll: parse client
    metadata (redirect_uris, `token_endpoint_auth_method: none`, grant_types
    authorization_code+refresh_token, scope mcp:read/mcp:write), build a public PKCE
    `RegisteredClient`, persist via `RegisteredClientRepository`, return the RFC 7591 response
    (client_id + echoed metadata). Full control over anonymity. **But** the SAS AS-metadata must
    then advertise `registration_endpoint` → inject it via the metadata customizer on
    `OAuth2AuthorizationServerConfigurer` (e.g. `authorizationServerMetadataEndpoint(m -> m.
    authorizationServerMetadataCustomizer(md -> md.claim("registration_endpoint", base+"/connect/register")))`).
    Confirm exact SAS 1.5.x API name.
  - **(B)** Enable SAS `oidc(o -> o.clientRegistrationEndpoint(...))` and relax its authentication
    to anonymous via a custom `AuthenticationConverter`/provider. Metadata then advertises
    `/connect/register` automatically, but making it anonymous fights the framework.
- Keep the seeded `dailytracker-mcp` client as fallback.
- **Harden the returned/registered redirect_uris** — only persist https callbacks (plus the known
  Claude/Inspector localhost callbacks). Do not accept arbitrary open redirect targets beyond what
  the RFC flow needs.

**Fase F — grant management**
- `GET /api/oauth/authorizations` (JWT): list grants for the user — query `oauth2_authorization`
  where `principal_name = userId`, join `oauth2_registered_client` for `client_name` + issued time.
- `DELETE /api/oauth/authorizations/{id}` (JWT, ownership-checked): `OAuth2AuthorizationService.remove`.

**Cleanup (do alongside)**
- `docs/mcp-server.md` still documents SSE endpoints `/mcp/sse` + `/mcp/message` — stale; transport
  is Streamable HTTP at `/mcp`. Rewrite: OAuth connect steps + dt_mcp_ fallback.
- Commit the current uncommitted WIP (`AuthorizationServerConfig`, `McpAuthFilter`,
  `ProtectedResourceMetadataController`, `V15`).

### Frontend (`/git/dailytracker-app`)

- **New route + page `/oauth/consent`** (`src/App.jsx` + e.g. `src/pages/OAuthConsent.jsx`): reads
  the authorize query params; requires logged-in user (JWT) — if not logged in, send to `/login`
  and return here after. Show client name + requested scopes with friendly labels and read/write
  checkboxes (read default on; write default on). Approve → `POST /api/oauth/consent` → on success
  `window.location.href = resumeUrl` (top-level nav to api origin). Deny → close / bounce to
  `redirect_uri?error=access_denied&state=…` (or just abort).
- **`McpModal.jsx` updates:**
  - Fix stale `SSE_URL = ${baseURL}/mcp/sse` → the connect URL is now `${baseURL}/mcp`.
  - New primary section: "Add custom connector" instructions — paste `${baseURL}/mcp`, leave client
    fields blank (DCR), authorize in browser.
  - New "Authorized apps" section: list + revoke via `/api/oauth/authorizations`.
  - Demote the `dt_mcp_` token panel to a collapsible "advanced / legacy" block.
- **i18n** pt-BR / en-US / es for all new strings (consent page + modal sections).

---

## 4. Production config / deploy (must-set, else it fails silently)

- **`MCP_OAUTH_JWK`** — REQUIRED in prod. Blank ⇒ ephemeral RSA key regenerated every restart ⇒
  all issued access/refresh tokens break on each deploy. Generate a stable 2048-bit RSA JWK JSON,
  store in the server `.env` (SSH deploy imports `optional:file:.env`). Simplest generation: boot
  once, copy the JWK from the WARN log in `AuthorizationServerConfig.rsaKey(...)`, paste into
  `.env`. **Security: this JWK contains the private key — `.env` only, never commit, never log in
  prod once set.**
- **`APP_PUBLIC_URL`** = exact `https://api.<domain>` (the OAuth issuer; metadata/issuer validation
  is strict — must match what Claude fetched).
- **`FRONTEND_URL`** = `https://app.<domain>` (consent redirect target + a seeded redirect_uri).
- **`server.forward-headers-strategy: framework`** already set — ensure the reverse proxy forwards
  `X-Forwarded-Proto`/`Host` so issuer + redirects use https (not the internal http origin).
- **Flyway V15** applies the SAS tables on deploy (verify it runs before first authorize).
- **CORS** (`CorsConfig.java`): the remote MCP connector runs server-side at Anthropic (not the
  browser), and `/oauth2/authorize` is a top-level browser redirect — so CORS is likely NOT a
  blocker. RISK to confirm: if any of `/.well-known/*`, `/oauth2/token`, `/connect/register`, `/mcp`
  are fetched from the `claude.ai` browser origin, current CORS (specific origins +
  allowCredentials) would block them. If needed, allow those specific paths for the connector
  origin(s).

---

## 5. Open risks to validate during implementation

1. **DCR anonymity** — the crux. Confirm option A vs B and that claude.ai actually calls
   `registration_endpoint` (vs prompting for a client id). Verify AS metadata advertises the
   registration endpoint.
2. **Ticket → SecurityContext** in the SAS chain — that a filter-set `Authentication` before the
   authorization endpoint is honored and, with `requireAuthorizationConsent(false)`, yields a code
   without a SAS consent page.
3. **Scope rewrite** actually limits the granted/token scopes to the approved subset.
4. **Streamable HTTP handshake** (initialize / tools/list / tools/call) succeeds with a real client
   (MCP Inspector, then Claude) carrying the OAuth Bearer JWT — including that tools run on the
   request thread so `McpPrincipalContext` (ThreadLocal) is visible (already true for dt_mcp_).
5. **issuer / forward-headers** behind the prod proxy — metadata issuer + redirect_uris come out as
   https and match exactly.
6. **JWKSource persistence** — `MCP_OAUTH_JWK` set and stable across restarts.
7. **Redirect_uri allowlist** — DCR-registered callbacks are constrained to safe https (+ known
   Claude/Inspector) targets; no open redirect.

---

## 6. Suggested build order (each step independently testable)

1. Commit current WIP. (baseline compiles + boots)
2. **Fase B** dual-token filter → test an OAuth JWT (minted manually / via Inspector) reaches /mcp.
3. **Fase D** consent bridge (entrypoint + `/api/oauth/consent` + ticket filter) → complete
   authorize→token with MCP Inspector (which supports DCR + PKCE) using the seeded client.
4. **Fase E** anonymous DCR + metadata registration_endpoint → Inspector/Claude self-registers.
5. Frontend `/oauth/consent` page + `App.jsx` route → real browser consent.
6. **Fase F** grants list/revoke + `McpModal.jsx` UI (OAuth primary, dt_mcp_ legacy, fix /mcp URL) + i18n.
7. Prod config (`MCP_OAUTH_JWK`, `APP_PUBLIC_URL`, `FRONTEND_URL`, proxy headers, CORS check) +
   rewrite `docs/mcp-server.md`.
8. **E2E**: real claude.ai "add custom connector" against the deployed https URL → authorize →
   read tasks + create a task from Claude.

---

## 7. Files touched (summary)

**Service (new)**: `OAuth2ConsentController` (`/api/oauth/consent`), `OAuthConsentTicketService`
(in-memory tickets), ticket `Filter`, custom `AuthenticationEntryPoint`, DCR
`ClientRegistrationController` (option A) + metadata customizer, `OAuthAuthorizationController`
(`/api/oauth/authorizations` list/revoke).
**Service (modify)**: `AuthorizationServerConfig` (entrypoint, ticket filter, DCR, metadata),
`McpAuthFilter` (dual-token), `docs/mcp-server.md`. Maybe `SecurityConfig` (permit
`/connect/register`, `/api/oauth/consent`), `CorsConfig` (if connector browser-origin).
**App (new)**: `pages/OAuthConsent.jsx` + route in `App.jsx`.
**App (modify)**: `McpModal.jsx`, i18n pt-BR/en-US/es.

Related memory: `project_mcp_server_plan`, `project_mcp_oauth_plan`.
