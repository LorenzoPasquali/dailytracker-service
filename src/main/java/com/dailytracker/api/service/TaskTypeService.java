package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.TaskTypeRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskTypeService {

    private final TaskTypeRepository taskTypeRepository;
    private final ProjectRepository projectRepository;

    public Map<String, Object> create(TaskTypeRequest request, Integer userId) {
        Project project = projectRepository.findByIdAndUserId(request.projectId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projeto não encontrado."));

        TaskType taskType = TaskType.builder()
                .name(request.name())
                .project(project)
                .build();

        taskType = taskTypeRepository.save(taskType);
        return Map.of("id", taskType.getId(), "name", taskType.getName(), "projectId", request.projectId());
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> update(Integer id, TaskTypeRequest request, Integer userId) {
        projectRepository.findByIdAndUserId(request.projectId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Projeto não encontrado."));

        TaskType taskType = taskTypeRepository.findByIdAndProject_UserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Tipo de tarefa não encontrado."));

        taskType.setName(request.name());
        taskType = taskTypeRepository.save(taskType);
        return toResponse(taskType);
    }

    public void delete(Integer id, Integer userId) {
        TaskType taskType = taskTypeRepository.findByIdAndProject_UserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Tipo de tarefa não encontrado."));
        taskTypeRepository.delete(taskType);
    }

    private Map<String, Object> toResponse(TaskType taskType) {
        return Map.of(
                "id", taskType.getId(),
                "name", taskType.getName(),
                "projectId", taskType.getProjectId()
        );
    }
}
