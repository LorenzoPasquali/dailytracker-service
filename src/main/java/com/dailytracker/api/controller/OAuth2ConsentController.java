package com.dailytracker.api.controller;

import com.dailytracker.api.service.OAuthConsentTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resource-owner side of the OAuth 2.1 consent bridge. Called by the SPA consent page (JWT-protected,
 * so the acting user is known). {@code GET /api/oauth/consent-info} describes the requesting client
 * and scopes for the UI; {@code POST /api/oauth/consent} records the user's read/write approval as a
 * one-time ticket and returns the URL that resumes {@code /oauth2/authorize} with the approved scopes.
 */
@RestController
@RequestMapping("/api/oauth")
@RequiredArgsConstructor
public class OAuth2ConsentController {

    private static final Set<String> SUPPORTED_SCOPES = Set.of("mcp:read", "mcp:write");

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuthConsentTicketService ticketService;

    @Value("${app.public-url}")
    private String publicUrl;

    public record ConsentRequest(Map<String, String> params, List<String> approvedScopes) {}

    @GetMapping("/consent-info")
    public Map<String, Object> consentInfo(@RequestParam("client_id") String clientId,
                                           @RequestParam(value = "scope", required = false) String scope) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown client");
        }
        List<String> requested = requestedScopes(scope).stream()
                .filter(client.getScopes()::contains)
                .filter(SUPPORTED_SCOPES::contains)
                .toList();
        return Map.of(
                "clientId", clientId,
                "clientName", client.getClientName(),
                "requestedScopes", requested.isEmpty() ? List.copyOf(client.getScopes()) : requested
        );
    }

    @PostMapping("/consent")
    public Map<String, Object> consent(@RequestBody ConsentRequest request, Authentication authentication) {
        Integer userId = ((Number) authentication.getPrincipal()).intValue();

        Map<String, String> params = request.params();
        if (params == null || params.get("client_id") == null || params.get("redirect_uri") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing authorize parameters");
        }
        String clientId = params.get("client_id");
        String redirectUri = params.get("redirect_uri");

        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown client");
        }
        if (!client.getRedirectUris().contains(redirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unregistered redirect_uri");
        }

        // Approved ⊆ requested ⊆ client scopes ⊆ supported. Empty approval is not a valid consent.
        Set<String> requested = Set.copyOf(requestedScopes(params.get("scope")));
        Set<String> approved = (request.approvedScopes() == null ? List.<String>of() : request.approvedScopes()).stream()
                .filter(requested::contains)
                .filter(client.getScopes()::contains)
                .filter(SUPPORTED_SCOPES::contains)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (approved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one scope must be approved");
        }

        String ticket = ticketService.mint(userId, approved);
        return Map.of("resumeUrl", buildResumeUrl(params, approved, ticket));
    }

    /** Rebuilds the {@code /oauth2/authorize} URL, narrowing {@code scope} to the approved set and adding the ticket. */
    private String buildResumeUrl(Map<String, String> params, Set<String> approvedScopes, String ticket) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.put("scope", String.join(" ", approvedScopes));
        out.put("_ticket", ticket);
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        String query = out.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return base + "/oauth2/authorize?" + query;
    }

    private static List<String> requestedScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scope.trim().split("[\\s+]+")).filter(s -> !s.isBlank()).toList();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
