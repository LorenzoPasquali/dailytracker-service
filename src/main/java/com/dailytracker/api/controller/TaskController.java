package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.TaskRequest;
import com.dailytracker.api.dto.request.TaskUpdateRequest;
import com.dailytracker.api.service.TaskService;
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

    @GetMapping
    public List<Map<String, Object>> getAll(Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        return taskService.findAllByUser(userId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TaskRequest request,
                                                       Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        Map<String, Object> task = taskService.create(request, userId);
        return ResponseEntity.status(201).body(task);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Integer id,
                                      @Valid @RequestBody TaskUpdateRequest request,
                                      Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        return taskService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        taskService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
