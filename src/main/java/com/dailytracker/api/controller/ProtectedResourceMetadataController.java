package com.dailytracker.api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP endpoint. A native AI connector
 * discovers which authorization server guards {@code /mcp} by fetching this document (pointed to by
 * the {@code WWW-Authenticate} header on a 401 from {@code /mcp}). Served unauthenticated.
 */
@RestController
public class ProtectedResourceMetadataController {

    private final String resource;
    private final String authorizationServer;

    public ProtectedResourceMetadataController(@Value("${app.public-url}") String publicUrl) {
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.resource = base + "/mcp";
        this.authorizationServer = base;
    }

    // Plain well-known + the path-suffixed form clients derive from the /mcp resource path.
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> metadata() {
        return Map.of(
                "resource", resource,
                "authorization_servers", List.of(authorizationServer),
                "scopes_supported", List.of("mcp:read", "mcp:write"),
                "bearer_methods_supported", List.of("header")
        );
    }
}
