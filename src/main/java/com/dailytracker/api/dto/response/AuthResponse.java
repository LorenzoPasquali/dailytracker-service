package com.dailytracker.api.dto.response;

import lombok.Builder;

@Builder
public record AuthResponse(
    String token,
    String refreshToken,
    String type
) {
    public AuthResponse(String token, String refreshToken) {
        this(token, refreshToken, "Bearer");
    }
}
