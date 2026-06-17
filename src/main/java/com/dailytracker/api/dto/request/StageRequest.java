package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StageRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 20) String color,
        Boolean isFinal
) {}
