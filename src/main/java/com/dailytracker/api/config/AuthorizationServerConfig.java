package com.dailytracker.api.config;

import com.dailytracker.api.security.oauth.ConsentRedirectAuthenticationEntryPoint;
import com.dailytracker.api.security.oauth.ConsentTicketFilter;
import com.dailytracker.api.service.OAuthConsentTicketService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth 2.1 Authorization Server for the MCP endpoint. Lets external AI tools connect via their
 * native "add custom connector" flow (OAuth discovery + PKCE) instead of a hand-edited opaque
 * token. Coexists with the legacy {@code dt_mcp_} bearer path (see {@code McpAuthFilter}).
 *
 * <p>Clients, authorizations and consents are persisted via JDBC (Flyway {@code V15}). The
 * resource-owner consent bridge (SPA + JWT) lands in a later phase; the issued access token already
 * carries a {@code userId} claim derived from the authenticated principal so {@code /mcp} can scope
 * by user.
 */
@Configuration
@Slf4j
public class AuthorizationServerConfig {

    private static final String MCP_CLIENT_ID = "dailytracker-mcp";

    /** Highest-priority chain: claims only the OAuth2 AS endpoints (/oauth2/*, /.well-known/*). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuthConsentTicketService consentTicketService,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.public-url}") String publicUrl) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;

        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        .oidc(Customizer.withDefaults())
                        // Advertise our anonymous Dynamic Client Registration endpoint (RFC 7591,
                        // served by ClientRegistrationController) so native connectors can self-register.
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(claims ->
                                        claims.claim("registration_endpoint", base + "/connect/register"))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // Browser hitting /oauth2/authorize without a session → bounce to the SPA consent page,
                // which resumes the flow with a one-time ticket (see ConsentTicketFilter).
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new ConsentRedirectAuthenticationEntryPoint(frontendUrl),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // AS management endpoints accept the bearer JWTs this server issues.
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                // Exchange the consent ticket for an authenticated principal early in the chain (right
                // after the security context is loaded), so the authorization endpoint sees the user.
                .addFilterAfter(new ConsentTicketFilter(consentTicketService), SecurityContextHolderFilter.class);

        return http.build();
    }

    // ── Persistence (JDBC) ──────────────────────────────────────────────────────

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * Seeds one pre-registered public client so connectors work even when Dynamic Client
     * Registration is unavailable: the user pastes {@code dailytracker-mcp} into the connector's
     * optional "Client ID" field. Public (no secret) + PKCE required. Idempotent on startup.
     */
    @Bean
    public CommandLineRunner seedMcpClient(
            RegisteredClientRepository repository,
            @Value("${app.frontend-url}") String frontendUrl) {
        return args -> {
            if (repository.findByClientId(MCP_CLIENT_ID) != null) {
                return;
            }
            RegisteredClient mcpClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(MCP_CLIENT_ID)
                    .clientName("DailyTracker MCP")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    // Known connector callbacks (MCP Inspector + Claude). DCR widens this later.
                    .redirectUri("http://localhost:6274/oauth/callback")
                    .redirectUri("http://127.0.0.1:6274/oauth/callback")
                    .redirectUri("https://claude.ai/api/mcp/auth_callback")
                    .redirectUri(frontendUrl + "/oauth/callback")
                    .scope("mcp:read")
                    .scope("mcp:write")
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)              // PKCE mandatory
                            .requireAuthorizationConsent(false) // consent is collected in our own SPA UI
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(Duration.ofHours(1))
                            .refreshTokenTimeToLive(Duration.ofDays(30))
                            .reuseRefreshTokens(false)
                            .build())
                    .build();
            repository.save(mcpClient);
            log.info("Seeded OAuth registered client '{}'", MCP_CLIENT_ID);
        };
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────

    /**
     * Stamps the access token with the acting user's id so {@code /mcp} can resolve scope from the
     * credential. The principal name is the app user id (set by the consent bridge in a later phase).
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().claim("userId", context.getPrincipal().getName());
            }
        };
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${app.public-url}") String publicUrl) {
        return AuthorizationServerSettings.builder()
                .issuer(publicUrl)
                .build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * The RSA key that signs access tokens. Loaded from {@code app.oauth.jwk} (a JWK JSON) so it
     * stays stable across restarts; a blank value generates an ephemeral key and logs it, which is
     * fine for local dev but breaks live tokens on every restart in production.
     */
    @Bean
    public RSAKey rsaKey(@Value("${app.oauth.jwk:}") String jwkJson) throws Exception {
        if (jwkJson != null && !jwkJson.isBlank()) {
            return RSAKey.parse(jwkJson);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        log.warn("app.oauth.jwk not set — generated an EPHEMERAL OAuth signing key. "
                + "Tokens will not survive a restart. For persistence set MCP_OAUTH_JWK to this JWK "
                + "(dev only — contains a private key):\n{}", key.toJSONString());
        return key;
    }
}
