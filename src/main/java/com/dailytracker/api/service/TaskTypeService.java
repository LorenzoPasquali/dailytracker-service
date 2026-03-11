package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.TaskTypeRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskTypeService {

    private final TaskTypeRepository taskTypeRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceEventPublisher eventPublisher;
    private final MessageService messageService;

    @Transactional
    public Map<String, Object> create(TaskTypeRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        Project project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));

        TaskType taskType = TaskType.builder()
                .name(request.name())
                .project(project)
                .build();

        taskType = taskTypeRepository.saveAndFlush(taskType);
        Map<String, Object> response = toResponse(taskType);
        eventPublisher.publishTaskTypeEvent(workspaceId, "TASK_TYPE_CREATED", response);
        return response;
    }

    @Transactional
    public Map<String, Object> update(Integer id, TaskTypeRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));

        TaskType taskType = taskTypeRepository.findByIdAndProject_WorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task_type.not_found")));

        taskType.setName(request.name());
        taskType = taskTypeRepository.saveAndFlush(taskType);
        Map<String, Object> response = toResponse(taskType);
        eventPublisher.publishTaskTypeEvent(workspaceId, "TASK_TYPE_UPDATED", response);
        return response;
    }

    @Transactional
    public void delete(Integer id, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        TaskType taskType = taskTypeRepository.findByIdAndProject_WorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task_type.not_found")));

        Integer projectId = taskType.getProject().getId();
        taskTypeRepository.delete(taskType);
        eventPublisher.publishTaskTypeEvent(workspaceId, "TASK_TYPE_DELETED", Map.of("id", id, "projectId", projectId));
    }

    private Map<String, Object> toResponse(TaskType taskType) {
        return Map.of(
                "id", taskType.getId(),
                "name", taskType.getName(),
                "projectId", taskType.getProject().getId()
        );
    }
}
