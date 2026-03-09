package com.dailytracker.api.dto.request;

import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
        @Size(max = 200) String title,
        @Size(max = 5000) String description,
        String status,
        String priority,
        Integer projectId,
        Integer taskTypeId
) {}
