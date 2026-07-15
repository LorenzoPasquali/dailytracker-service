package com.dailytracker.api.security.oauth;

import com.dailytracker.api.service.OAuthConsentTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Completes the SPA consent bridge on the resumed {@code /oauth2/authorize} request: it exchanges a
 * one-time {@code _ticket} parameter for an authenticated principal (the app user id) in the
 * {@link SecurityContextHolder}, so the Authorization Server can issue an authorization code without
 * a server-side login session. The requested scopes were already narrowed to the approved set in the
 * resume URL; the client is configured with {@code requireAuthorizationConsent(false)}, so no
 * Authorization Server consent page is shown.
 */
@RequiredArgsConstructor
public class ConsentTicketFilter extends OncePerRequestFilter {

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TICKET_PARAM = "_ticket";

    private final OAuthConsentTicketService ticketService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ticket = request.getParameter(TICKET_PARAM);
        if (ticket != null && AUTHORIZE_PATH.equals(request.getRequestURI())) {
            Optional<OAuthConsentTicketService.Ticket> resolved = ticketService.consume(ticket);
            resolved.ifPresent(t -> {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(t.userId().toString(), null, List.of());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        filterChain.doFilter(request, response);
    }
}
