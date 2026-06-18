package com.dailytracker.api.mcp;

import com.dailytracker.api.service.McpTokenService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates external MCP requests on {@code /mcp/**} using a per-user MCP token presented as
 * {@code Authorization: Bearer dt_mcp_...}. On success it publishes the resolved user, their
 * personal workspace, and the token's read-only flag to {@link McpPrincipalContext} for the
 * duration of the request, so MCP tools scope every operation to that user. Unrecognized or
 * expired tokens get a 401 and never reach the tool layer.
 */
@Component
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    private final McpTokenService mcpTokenService;
    private final WorkspaceService workspaceService;

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

        Optional<McpTokenService.ResolvedToken> resolved = mcpTokenService.validateAndTouch(token);
        if (resolved.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Invalid or expired MCP token\"}");
            return;
        }

        try {
            Integer userId = resolved.get().userId();
            Integer workspaceId = workspaceService.getPersonalWorkspaceId(userId);
            McpPrincipalContext.set(userId, workspaceId, resolved.get().readOnly());
            filterChain.doFilter(request, response);
        } finally {
            McpPrincipalContext.clear();
        }
    }
}
