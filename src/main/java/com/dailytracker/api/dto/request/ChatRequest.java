package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ChatRequest(
        @NotNull @NotEmpty List<Map<String, String>> history
) {
}
