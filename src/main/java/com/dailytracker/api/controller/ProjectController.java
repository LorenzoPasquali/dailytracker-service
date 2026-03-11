package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.ProjectRequest;
import com.dailytracker.api.service.ProjectService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<Map<String, Object>> getAll(
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return projectService.findAllByWorkspace(wsId, userId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody ProjectRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return ResponseEntity.status(201).body(projectService.create(request, userId, wsId));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable Integer id,
            @Valid @RequestBody ProjectRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return projectService.update(id, request, userId, wsId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Integer id,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        projectService.delete(id, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    private int userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }

    private int resolveWorkspace(Integer workspaceId, int userId) {
        return workspaceId != null ? workspaceId : workspaceService.getPersonalWorkspaceId(userId);
    }
}
