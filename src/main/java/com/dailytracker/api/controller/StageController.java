package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.StageRequest;
import com.dailytracker.api.service.StageService;
import com.dailytracker.api.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stages")
@RequiredArgsConstructor
public class StageController {

    private final StageService stageService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return stageService.list(userId, wsId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody StageRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return ResponseEntity.status(201).body(stageService.create(request, userId, wsId));
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @RequestBody List<Map<String, Object>> items,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        stageService.reorder(items, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable Integer id,
            @Valid @RequestBody StageRequest request,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        return stageService.update(id, request, userId, wsId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Integer id,
            @RequestParam(required = false) Integer targetStageId,
            @RequestParam(required = false) Integer workspaceId,
            Authentication auth) {
        int userId = userId(auth);
        int wsId = resolveWorkspace(workspaceId, userId);
        stageService.delete(id, targetStageId, userId, wsId);
        return ResponseEntity.noContent().build();
    }

    private int userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }

    private int resolveWorkspace(Integer workspaceId, int userId) {
        return workspaceId != null ? workspaceId : workspaceService.getPersonalWorkspaceId(userId);
    }
}
