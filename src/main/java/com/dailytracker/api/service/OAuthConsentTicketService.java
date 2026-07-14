package com.dailytracker.api.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the OAuth 2.1 authorize step across the SPA. The API origin has no session for the app
 * user (they authenticate with a JWT held by the SPA), so {@code /oauth2/authorize} cannot resolve a
 * resource owner on its own. Instead the SPA consent page mints a short-lived, single-use ticket
 * here after the user approves; the ticket is then presented back on the resumed
 * {@code /oauth2/authorize} request, where {@code ConsentTicketFilter} exchanges it for an
 * authenticated principal.
 *
 * <p>Tickets are held in memory: the deployment is single-instance and the TTL is ~60s, so losing
 * pending tickets on restart is acceptable. Each ticket is valid once.
 */
@Service
public class OAuthConsentTicketService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int TICKET_BYTES = 32;

    /** Approved consent for one pending authorize request. */
    public record Ticket(Integer userId, Set<String> approvedScopes, Instant expiresAt) {}

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** Mints a single-use ticket capturing who consented and to which scopes. */
    public String mint(Integer userId, Set<String> approvedScopes) {
        purgeExpired();
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new Ticket(userId, Set.copyOf(approvedScopes), Instant.now().plus(TTL)));
        return ticket;
    }

    /** Validates and consumes a ticket. Empty if unknown, already used, or expired. */
    public Optional<Ticket> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Ticket found = tickets.remove(ticket);
        if (found == null || found.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
    }
}
