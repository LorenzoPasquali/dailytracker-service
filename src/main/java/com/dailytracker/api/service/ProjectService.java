package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.ProjectRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllByUser(Integer userId) {
        return projectRepository.findByUserIdOrderByNameAsc(userId)
                .stream()
                .map(this::toResponseWithTaskTypes)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(ProjectRequest request, Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        Project project = Project.builder()
                .name(request.name())
                .color(request.color())
                .user(user)
                .build();

        project = projectRepository.save(project);
        return toResponse(project, userId);
    }

    @Transactional
    public Map<String, Object> update(Integer id, ProjectRequest request, Integer userId) {
        Project project = projectRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));

        if (request.name() != null) {
            project.setName(request.name());
        }
        if (request.color() != null) {
            project.setColor(request.color());
        }

        project = projectRepository.save(project);
        return toResponseWithTaskTypes(project);
    }

    public void delete(Integer id, Integer userId) {
        Project project = projectRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));

        try {
            projectRepository.delete(project);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException(messageService.get("error.project.has_tasks"));
        }
    }

    private Map<String, Object> toResponse(Project project, Integer userId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("color", project.getColor());
        map.put("userId", userId);
        map.put("taskTypes", List.of());
        return map;
    }

    private Map<String, Object> toResponseWithTaskTypes(Project project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("color", project.getColor());
        map.put("userId", project.getUser().getId());
        map.put("taskTypes", project.getTaskTypes() != null
                ? project.getTaskTypes().stream().map(tt -> Map.of(
                    "id", tt.getId(),
                    "name", tt.getName(),
                    "projectId", tt.getProjectId()
                )).toList()
                : List.of());
        return map;
    }
}
