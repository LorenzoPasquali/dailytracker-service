package com.dailytracker.api.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Entry point for the Authorization Server chain: an unauthenticated (browser) hit on
 * {@code /oauth2/authorize} is 302-redirected to the SPA consent page, preserving the original
 * authorize query string. The SPA — where the user is logged in with the app JWT — collects the
 * read/write consent and resumes the flow via a one-time ticket (see {@code OAuthConsentTicketService}).
 */
public class ConsentRedirectAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String consentUrl;

    public ConsentRedirectAuthenticationEntryPoint(String frontendUrl) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        this.consentUrl = base + "/oauth/consent";
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String query = request.getQueryString();
        response.sendRedirect(query != null ? consentUrl + "?" + query : consentUrl);
    }
}
