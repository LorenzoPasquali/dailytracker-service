package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.TaskTypeRequest;
import com.dailytracker.api.service.TaskTypeService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/task-types")
@RequiredArgsConstructor
public class TaskTypeController {

    private final TaskTypeService taskTypeService;
    private final WorkspaceService workspaceService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody TaskTypeRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return ResponseEntity.status(201).body(taskTypeService.create(request, userId, wsId));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable Integer id,
            @Valid @RequestBody TaskTypeRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return taskTypeService.update(id, request, userId, wsId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Integer id,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        taskTypeService.delete(id, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    private int userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }

    private int resolveWorkspace(Integer workspaceId, int userId) {
        return workspaceId != null ? workspaceId : workspaceService.getPersonalWorkspaceId(userId);
    }
}
