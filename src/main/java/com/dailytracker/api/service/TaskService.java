package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.TaskRequest;
import com.dailytracker.api.dto.request.TaskUpdateRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import com.dailytracker.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final TaskTypeRepository taskTypeRepository;
    private final MessageService messageService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllByUser(Integer userId) {
        return taskRepository.findByUserIdOrdered(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(TaskRequest request, Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        String priority = request.priority() != null ? request.priority() : "MEDIUM";
        Integer position = resolvePositionForNewTask(userId, request.status(), priority);

        Task task = Task.builder()
                .title(request.title())
                .description(request.description())
                .status(request.status())
                .priority(priority)
                .position(position)
                .user(user)
                .build();

        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndUserId(request.projectId(), userId)
                    .orElse(null);
            task.setProject(project);
        }

        if (request.taskTypeId() != null) {
            TaskType taskType = taskTypeRepository.findById(request.taskTypeId())
                    .orElse(null);
            task.setTaskType(taskType);
        }

        task = taskRepository.save(task);
        return toResponse(task, userId, request.projectId(), request.taskTypeId());
    }

    @Transactional
    public Map<String, Object> update(Integer id, TaskUpdateRequest request, Integer userId) {
        Task task = taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task.not_found")));

        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.status() != null && !request.status().equals(task.getStatus())) {
            task.setStatus(request.status());
            task.setPosition(null);
        }
        if (request.priority() != null && !request.priority().equals(task.getPriority())) {
            task.setPriority(request.priority());
            task.setPosition(null);
        }
        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndUserId(request.projectId(), userId)
                    .orElse(null);
            task.setProject(project);
        }
        if (request.taskTypeId() != null) {
            TaskType taskType = taskTypeRepository.findById(request.taskTypeId())
                    .orElse(null);
            task.setTaskType(taskType);
        }

        task = taskRepository.save(task);
        return toResponse(task);
    }

    @Transactional
    public void reorder(List<Map<String, Object>> items, Integer userId) {
        for (Map<String, Object> item : items) {
            Integer id = (Integer) item.get("id");
            Integer position = (Integer) item.get("position");
            taskRepository.findByIdAndUserId(id, userId)
                    .ifPresent(task -> {
                        task.setPosition(position);
                        taskRepository.save(task);
                    });
        }
    }

    private Integer resolvePositionForNewTask(Integer userId, String status, String priority) {
        if ("HIGH".equals(priority)) {
            return taskRepository.findMinPositionByUserIdAndStatus(userId, status)
                    .map(min -> min - 10)
                    .orElse(null);
        } else {
            return taskRepository.findMaxPositionByUserIdAndStatus(userId, status)
                    .map(max -> max + 10)
                    .orElse(null);
        }
    }

    public void delete(Integer id, Integer userId) {
        Task task = taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task.not_found")));
        taskRepository.delete(task);
    }

    private Map<String, Object> toResponse(Task task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTitle());
        map.put("description", task.getDescription() != null ? task.getDescription() : "");
        map.put("status", task.getStatus());
        map.put("priority", task.getPriority() != null ? task.getPriority() : "MEDIUM");
        map.put("createdAt", task.getCreatedAt().toString());
        map.put("updatedAt", task.getUpdatedAt().toString());
        map.put("userId", task.getUser().getId());
        map.put("projectId", task.getProjectId());
        map.put("taskTypeId", task.getTaskTypeId());
        return map;
    }

    private Map<String, Object> toResponse(Task task, Integer userId, Integer projectId, Integer taskTypeId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTitle());
        map.put("description", task.getDescription() != null ? task.getDescription() : "");
        map.put("status", task.getStatus());
        map.put("priority", task.getPriority() != null ? task.getPriority() : "MEDIUM");
        map.put("createdAt", task.getCreatedAt().toString());
        map.put("updatedAt", task.getUpdatedAt().toString());
        map.put("userId", userId);
        map.put("projectId", projectId);
        map.put("taskTypeId", taskTypeId);
        return map;
    }
}
