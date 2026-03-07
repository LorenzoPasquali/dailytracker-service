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
        Integer userId = (Integer) auth.getPrincipal();
        return projectService.findAllByUser(userId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ProjectRequest request,
                                                       Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        Map<String, Object> project = projectService.create(request, userId);
        return ResponseEntity.status(201).body(project);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Integer id,
                                      @Valid @RequestBody ProjectRequest request,
                                      Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        return projectService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        projectService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
