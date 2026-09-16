package com.dailytracker.api.config;

import com.dailytracker.api.analytics.admin.AdminAuthFilter;
import com.dailytracker.api.mcp.McpAuthFilter;
import com.dailytracker.api.security.JwtAuthenticationFilter;
import com.dailytracker.api.security.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final McpAuthFilter mcpAuthFilter;
    private final AdminAuthFilter adminAuthFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsSource) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsSource))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/register",
                    "/auth/login",
                    "/auth/refresh",
                    "/auth/google",
                    "/auth/google/callback",
                    "/auth/consume-cookies",
                    "/oauth2/**",
                    "/healthz",
                    "/ws/**",
                    "/api/workspaces/invite/*",
                    "/public/pageview",
                    "/admin/login"
                ).permitAll()
                // Hidden analytics dashboard: AdminAuthFilter grants ROLE_ADMIN from the admin token.
                .requestMatchers("/admin/**").hasAuthority(AdminAuthFilter.ROLE_ADMIN)
                // /mcp/** is authenticated by McpAuthFilter (per-user MCP token), not JWT.
                .requestMatchers("/mcp/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            // Admin API is consumed by fetch/axios: answer 401 instead of redirecting to Google login.
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/admin/**")))
            .oauth2Login(oauth2 -> oauth2
                .redirectionEndpoint(re -> re.baseUri("/auth/google/callback"))
                .successHandler(oAuth2SuccessHandler)
            )
            .addFilterBefore(mcpAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
