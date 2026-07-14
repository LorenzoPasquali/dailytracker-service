package com.dailytracker.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Lets a user see and revoke the external apps (MCP connectors) they have authorized via OAuth.
 * JWT-protected like the rest of {@code /api/**}. Grants are the {@code oauth2_authorization} rows
 * whose {@code principal_name} is the acting user id.
 */
@RestController
@RequestMapping("/api/oauth/authorizations")
@RequiredArgsConstructor
public class OAuthAuthorizationController {

    private final JdbcTemplate jdbcTemplate;
    private final OAuth2AuthorizationService authorizationService;

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        String principal = String.valueOf(((Number) auth.getPrincipal()).intValue());
        return jdbcTemplate.query(
                """
                SELECT a.id,
                       c.client_name,
                       a.authorized_scopes,
                       COALESCE(a.access_token_issued_at, a.authorization_code_issued_at) AS authorized_at
                FROM oauth2_authorization a
                JOIN oauth2_registered_client c ON c.id = a.registered_client_id
                WHERE a.principal_name = ?
                ORDER BY authorized_at DESC NULLS LAST
                """,
                (rs, rowNum) -> Map.of(
                        "id", rs.getString("id"),
                        "clientName", rs.getString("client_name"),
                        "scopes", splitScopes(rs.getString("authorized_scopes")),
                        "authorizedAt", rs.getTimestamp("authorized_at") != null
                                ? rs.getTimestamp("authorized_at").toInstant().toString() : null
                ),
                principal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id, Authentication auth) {
        String principal = String.valueOf(((Number) auth.getPrincipal()).intValue());
        OAuth2Authorization authorization = authorizationService.findById(id);
        if (authorization == null || !principal.equals(authorization.getPrincipalName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found");
        }
        authorizationService.remove(authorization);
        return ResponseEntity.noContent().build();
    }

    private static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return List.of(scopes.split(","));
    }
}
