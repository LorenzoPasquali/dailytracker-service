package com.dailytracker.api.controller;

import com.dailytracker.api.dto.request.TaskTypeRequest;
import com.dailytracker.api.service.TaskTypeService;
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

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TaskTypeRequest request,
                                                       Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        Map<String, Object> taskType = taskTypeService.create(request, userId);
        return ResponseEntity.status(201).body(taskType);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Integer id,
                                      @Valid @RequestBody TaskTypeRequest request,
                                      Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        return taskTypeService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id, Authentication auth) {
        Integer userId = (Integer) auth.getPrincipal();
        taskTypeService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
