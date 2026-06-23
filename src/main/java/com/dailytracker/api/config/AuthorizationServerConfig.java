package com.dailytracker.api.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
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
 * <p>Phase 0 (spike): in-memory client + ephemeral-or-env signing key, just enough to validate
 * discovery metadata, the issuer URL, transport, and key persistence. The resource-owner consent
 * bridge (SPA + JWT) and persistent JDBC client/authorization storage land in later phases.
 */
@Configuration
@Slf4j
public class AuthorizationServerConfig {

    /** Highest-priority chain: claims only the OAuth2 AS endpoints (/oauth2/*, /.well-known/*, /connect/register). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        // OIDC enables Dynamic Client Registration (/connect/register) for zero-config connectors.
                        .oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // Browser hitting /oauth2/authorize unauthenticated → redirect to login (the SPA
                // consent bridge replaces this entry point in a later phase).
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // AS management endpoints (e.g. DCR) accept the bearer JWTs this server issues.
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * One pre-registered public client so connectors work even when Dynamic Client Registration
     * is unavailable: the user pastes {@code dailytracker-mcp} into the connector's optional
     * "Client ID" field. Public (no secret) + PKCE required.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${app.frontend-url}") String frontendUrl) {
        RegisteredClient mcpClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("dailytracker-mcp")
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
        return new InMemoryRegisteredClientRepository(mcpClient);
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
