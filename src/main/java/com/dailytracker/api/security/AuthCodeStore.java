package com.dailytracker.api.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthCodeStore {

    private static final long CODE_TTL_MS = 30_000; // 30 seconds
    private static final SecureRandom RANDOM = new SecureRandom();

    private record StoredTokens(String token, String refreshToken, long expiresAt) {}

    private final ConcurrentHashMap<String, StoredTokens> store = new ConcurrentHashMap<>();

    public String storeTokens(String token, String refreshToken) {
        evictExpired();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.put(code, new StoredTokens(token, refreshToken, System.currentTimeMillis() + CODE_TTL_MS));
        return code;
    }

    public String[] consumeCode(String code) {
        StoredTokens tokens = store.remove(code);
        if (tokens == null || System.currentTimeMillis() > tokens.expiresAt()) {
            if (tokens != null) {
                // Was expired — already removed by remove() above
            }
            return null;
        }
        return new String[]{tokens.token(), tokens.refreshToken()};
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
