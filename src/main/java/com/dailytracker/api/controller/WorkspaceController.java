package com.dailytracker.api.controller;

import com.dailytracker.api.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        return workspaceService.findAllForUser(userId(auth));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(201)
                .body(workspaceService.create(name.trim(), userId(auth)));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new com.dailytracker.api.exception.BadRequestException("Name is required.");
        }
        return workspaceService.update(id, name.trim(), userId(auth));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, Authentication auth) {
        workspaceService.delete(id, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/invite")
    public Map<String, Object> createInvite(@PathVariable Integer id, Authentication auth) {
        return workspaceService.createInviteToken(id, userId(auth));
    }

    @GetMapping("/invite/{token}")
    public Map<String, Object> invitePreview(@PathVariable String token) {
        return workspaceService.getInvitePreview(token);
    }

    @PostMapping("/invite/{token}/accept")
    public ResponseEntity<Void> acceptInvite(@PathVariable String token, Authentication auth) {
        workspaceService.acceptInvite(token, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public List<Map<String, Object>> getMembers(@PathVariable Integer id, Authentication auth) {
        return workspaceService.getMembers(id, userId(auth));
    }

    @DeleteMapping("/{id}/members/{targetUserId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable Integer id,
            @PathVariable Integer targetUserId,
            Authentication auth) {
        workspaceService.removeMember(id, targetUserId, userId(auth));
        return ResponseEntity.noContent().build();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Integer userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }
}
