package com.dailytracker.api.dto.response;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String reply,
        List<Map<String, String>> history,
        boolean tasksCreated
) {
}
