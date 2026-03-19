package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NotificationRuleRequest(
        @NotBlank @Size(max = 120) String name,
        Integer projectId,
        @NotEmpty List<String> emails,
        @NotEmpty List<Integer> offsets,
        Boolean isActive
) {}
