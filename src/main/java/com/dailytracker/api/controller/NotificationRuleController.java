package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.NotificationRuleRequest;
import com.dailytracker.api.service.NotificationRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notification-rules")
@RequiredArgsConstructor
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam Integer workspaceId,
            Authentication auth) {
        return ResponseEntity.ok(ruleService.listRules(workspaceId, userId(auth)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestParam Integer workspaceId,
            @Valid @RequestBody NotificationRuleRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ruleService.createRule(request, userId(auth), workspaceId));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Integer ruleId,
            @RequestParam Integer workspaceId,
            @Valid @RequestBody NotificationRuleRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ruleService.updateRule(ruleId, request, userId(auth), workspaceId));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(
            @PathVariable Integer ruleId,
            @RequestParam Integer workspaceId,
            Authentication auth) {
        ruleService.deleteRule(ruleId, userId(auth), workspaceId);
        return ResponseEntity.noContent().build();
    }

    private int userId(Authentication auth) {
        return ((Number) auth.getPrincipal()).intValue();
    }
}
