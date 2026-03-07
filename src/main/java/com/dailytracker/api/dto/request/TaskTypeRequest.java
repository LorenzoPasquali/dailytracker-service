package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskTypeRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Integer projectId
) {}
