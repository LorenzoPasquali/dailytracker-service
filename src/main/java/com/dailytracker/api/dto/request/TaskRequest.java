package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record TaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotBlank String status,
        String priority,
        Integer projectId,
        Integer taskTypeId,
        Integer assigneeId,
        Instant dueDate
) {}
