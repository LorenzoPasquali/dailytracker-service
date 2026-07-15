package com.dailytracker.api.mcp;

import com.dailytracker.api.service.McpTokenService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates external MCP requests on {@code /mcp/**}. Accepts two credential kinds on the
 * {@code Authorization: Bearer} header:
 * <ul>
 *   <li>a legacy per-user opaque token {@code dt_mcp_...} (validated by {@link McpTokenService});</li>
 *   <li>an OAuth 2.1 access token (RSA JWT issued by this app's Authorization Server) — used by the
 *       native "add custom connector" flow. Its {@code userId} claim identifies the user and its
 *       {@code scope} claim gates writes ({@code mcp:write} present ⇒ read-write).</li>
 * </ul>
 * On success it publishes the resolved user, their personal workspace, and the read-only flag to
 * {@link McpPrincipalContext} for the duration of the request, so MCP tools scope every operation to
 * that user. Unrecognized, expired or malformed credentials get a 401 that points native connectors
 * at the Protected Resource Metadata (RFC 9728) so they can start the OAuth flow.
 */
@Component
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    private static final String LEGACY_TOKEN_PREFIX = "dt_mcp_";
    private static final String WRITE_SCOPE = "mcp:write";

    private final McpTokenService mcpTokenService;
    private final WorkspaceService workspaceService;
    private final JwtDecoder jwtDecoder;

    @Value("${app.public-url}")
    private String publicUrl;

    /** Resolved MCP principal for a request. */
    private record McpAuth(Integer userId, Integer workspaceId, boolean readOnly) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;

        Optional<McpAuth> auth = authenticate(token);
        if (auth.isEmpty()) {
            // Point native connectors at the Protected Resource Metadata (RFC 9728) so they can
            // discover the authorization server and start the OAuth flow.
            String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
            response.setHeader("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Invalid or expired MCP credential\"}");
            return;
        }

        try {
            McpAuth a = auth.get();
            McpPrincipalContext.set(a.userId(), a.workspaceId(), a.readOnly());
            filterChain.doFilter(request, response);
        } finally {
            McpPrincipalContext.clear();
        }
    }

    private Optional<McpAuth> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (token.startsWith(LEGACY_TOKEN_PREFIX)) {
            return mcpTokenService.validateAndTouch(token)
                    .map(r -> new McpAuth(r.userId(), workspaceService.getPersonalWorkspaceId(r.userId()), r.readOnly()));
        }
        return authenticateOAuth(token);
    }

    private Optional<McpAuth> authenticateOAuth(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String userIdClaim = jwt.getClaimAsString("userId");
            if (userIdClaim == null) {
                return Optional.empty();
            }
            Integer userId = Integer.valueOf(userIdClaim);
            List<String> scopes = jwt.getClaimAsStringList("scope");
            boolean readOnly = scopes == null || !scopes.contains(WRITE_SCOPE);
            Integer workspaceId = workspaceService.getPersonalWorkspaceId(userId);
            return Optional.of(new McpAuth(userId, workspaceId, readOnly));
        } catch (Exception e) {
            // Bad signature, expired, wrong issuer, non-numeric userId, etc. → treat as unauthenticated.
            return Optional.empty();
        }
    }
}
