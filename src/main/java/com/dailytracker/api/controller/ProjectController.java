package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.ProjectRequest;
import com.dailytracker.api.service.ProjectService;
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

    @GetMapping
    public List<Map<String, Object>> getAll(Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        return projectService.findAllByUser(userId.intValue());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ProjectRequest request,
                                                       Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        Map<String, Object> project = projectService.create(request, userId.intValue());
        return ResponseEntity.status(201).body(project);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Integer id,
                                      @Valid @RequestBody ProjectRequest request,
                                      Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        return projectService.update(id, request, userId.intValue());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, Authentication auth) {
        Number userId = (Number) auth.getPrincipal();
        projectService.delete(id, userId.intValue());
        return ResponseEntity.noContent().build();
    }
}
