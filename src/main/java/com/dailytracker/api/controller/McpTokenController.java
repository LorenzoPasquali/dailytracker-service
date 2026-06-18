package com.dailytracker.api.controller;

import com.dailytracker.api.service.McpTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Manages a user's MCP access tokens (used by external clients such as Claude Desktop).
 * Protected by the standard JWT auth like the rest of {@code /api/**}; the tokens themselves
 * authenticate the separate {@code /mcp/**} endpoint via {@code McpAuthFilter}.
 */
@RestController
@RequestMapping("/api/mcp/tokens")
@RequiredArgsConstructor
public class McpTokenController {

    private final McpTokenService mcpTokenService;

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        return mcpTokenService.list(userId(auth));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        Map<String, Object> b = body != null ? body : Map.of();
        String label = b.get("label") != null ? b.get("label").toString() : null;
        boolean readOnly = Boolean.TRUE.equals(b.get("readOnly"));
        Integer expiresInDays = b.get("expiresInDays") instanceof Number n ? n.intValue() : null;
        return mcpTokenService.create(userId(auth), label, readOnly, expiresInDays);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Integer id, Authentication auth) {
        mcpTokenService.revoke(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    private Integer userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }
}
