package com.dailytracker.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anonymous Dynamic Client Registration (RFC 7591) for MCP connectors. The claude.ai web connector
 * has no field to paste a pre-registered client id, so it self-registers here before starting the
 * OAuth flow. Registration is unauthenticated by design (public PKCE clients), so it is deliberately
 * narrow: it always mints a public client (no secret) requiring PKCE, limited to the
 * {@code mcp:read}/{@code mcp:write} scopes and the authorization_code + refresh_token grants, and it
 * only accepts https (or loopback) redirect URIs.
 *
 * <p>The endpoint is advertised as {@code registration_endpoint} in the Authorization Server metadata
 * (see {@code AuthorizationServerConfig}).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ClientRegistrationController {

    private final RegisteredClientRepository registeredClientRepository;

    @PostMapping("/connect/register")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        List<String> redirectUris = asStringList(body.get("redirect_uris"));
        if (redirectUris.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uris is required");
        }
        redirectUris.forEach(ClientRegistrationController::assertSafeRedirectUri);

        String clientName = body.get("client_name") instanceof String s && !s.isBlank() ? s : "MCP Client";
        String clientId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientIdIssuedAt(issuedAt)
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .scope("mcp:read")
                .scope("mcp:write")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)              // PKCE mandatory for public clients
                        .requireAuthorizationConsent(false) // consent is collected in our SPA UI
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        registeredClientRepository.save(client);
        log.info("Dynamically registered MCP client '{}' ({})", clientName, clientId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", issuedAt.getEpochSecond());
        response.put("client_name", clientName);
        response.put("redirect_uris", redirectUris);
        response.put("grant_types", List.of("authorization_code", "refresh_token"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        response.put("scope", "mcp:read mcp:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    /** Only https (any host) or loopback http (MCP Inspector / local dev) are acceptable callbacks. */
    private static void assertSafeRedirectUri(String uri) {
        try {
            URI parsed = new URI(uri);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            boolean ok = "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback);
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri must be https or loopback: " + uri);
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid redirect_uri: " + uri);
        }
    }
}
