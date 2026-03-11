package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.TaskRequest;
import com.dailytracker.api.dto.request.TaskUpdateRequest;
import com.dailytracker.api.service.TaskService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<Map<String, Object>> getAll(
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return taskService.findAllByWorkspace(wsId, userId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody TaskRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return ResponseEntity.status(201).body(taskService.create(request, userId, wsId));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable Integer id,
            @Valid @RequestBody TaskUpdateRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return taskService.update(id, request, userId, wsId);
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @RequestBody List<Map<String, Object>> items,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        taskService.reorder(items, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Integer id,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        taskService.delete(id, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    private int userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }

    private int resolveWorkspace(Integer workspaceId, int userId) {
        return workspaceId != null ? workspaceId : workspaceService.getPersonalWorkspaceId(userId);
    }
}
