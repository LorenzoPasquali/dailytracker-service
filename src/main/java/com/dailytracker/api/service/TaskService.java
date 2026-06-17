package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.TaskRequest;
import com.dailytracker.api.dto.request.TaskUpdateRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Stage;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.entity.Workspace;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.StageRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import com.dailytracker.api.repository.UserRepository;
import com.dailytracker.api.repository.WorkspaceRepository;
import org.springframework.context.annotation.Lazy;
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
    private final StageRepository stageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceEventPublisher eventPublisher;
    private final MessageService messageService;
    @Lazy
    private final NotificationScheduleService notificationScheduleService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllByWorkspace(Integer workspaceId, Integer userId) {
        workspaceService.assertMember(workspaceId, userId);
        return taskRepository.findByWorkspaceIdOrdered(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(TaskRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.workspace.not_found")));

        Stage stage = resolveStageOrDefault(request.stageId(), workspaceId);

        String priority = request.priority() != null ? request.priority() : "MEDIUM";
        Integer position = resolvePositionForNewTask(workspaceId, stage, priority);

        Task task = Task.builder()
                .title(request.title())
                .description(request.description())
                .status(stage.getName())
                .stage(stage)
                .priority(priority)
                .position(position)
                .dueDate(request.dueDate())
                .user(user)
                .workspace(workspace)
                .build();

        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));
            task.setProject(project);
        }

        if (request.taskTypeId() != null) {
            TaskType taskType = taskTypeRepository.findByIdAndProject_WorkspaceId(request.taskTypeId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task_type.not_found")));
            task.setTaskType(taskType);
        }

        if (request.assigneeId() != null) {
            User assignee = userRepository.findById(request.assigneeId()).orElse(null);
            task.setAssignee(assignee);
        }

        task = taskRepository.saveAndFlush(task);
        if (task.getDueDate() != null) {
            notificationScheduleService.recomputeForTask(task);
        }
        Map<String, Object> response = toResponse(task);
        eventPublisher.publishTaskEvent(workspaceId, "TASK_CREATED", response);
        return response;
    }

    @Transactional
    public Map<String, Object> update(Integer id, TaskUpdateRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        Task task = taskRepository.findByIdAndWorkspaceIdWithUsers(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task.not_found")));

        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.stageId() != null && !request.stageId().equals(task.getStageId())) {
            Stage stage = stageRepository.findByIdAndWorkspaceId(request.stageId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));
            task.setStage(stage);
            task.setStatus(stage.getName());
            task.setPosition(null);
        }
        if (request.priority() != null && !request.priority().equals(task.getPriority())) {
            task.setPriority(request.priority());
            task.setPosition(null);
        }
        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));
            task.setProject(project);
        }
        if (request.taskTypeId() != null) {
            TaskType taskType = taskTypeRepository.findByIdAndProject_WorkspaceId(request.taskTypeId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task_type.not_found")));
            task.setTaskType(taskType);
        }
        if (request.createdAt() != null) {
            task.setCreatedAt(request.createdAt());
        }
        if (request.dueDate() != null || (request.title() != null && task.getDueDate() != null)) {
            task.setDueDate(request.dueDate());
        }
        // Only update assignee on full form edits (title present). Partial updates (e.g. drag-and-drop)
        // do not include title, so they leave assignee unchanged.
        if (request.title() != null) {
            User assignee = request.assigneeId() != null
                    ? userRepository.findById(request.assigneeId()).orElse(null)
                    : null;
            task.setAssignee(assignee);
        }

        task = taskRepository.saveAndFlush(task);
        notificationScheduleService.recomputeForTask(task);
        Map<String, Object> response = toResponse(task);
        eventPublisher.publishTaskEvent(workspaceId, "TASK_UPDATED", response);
        return response;
    }

    @Transactional
    public void reorder(List<Map<String, Object>> items, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);
        for (Map<String, Object> item : items) {
            Integer id = (Integer) item.get("id");
            Integer position = (Integer) item.get("position");
            taskRepository.findByIdAndWorkspaceId(id, workspaceId)
                    .ifPresent(task -> {
                        task.setPosition(position);
                        taskRepository.save(task);
                    });
        }
        eventPublisher.publishTaskEvent(workspaceId, "TASK_REORDERED", Map.of("items", items));
    }

    public void delete(Integer id, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);
        Task task = taskRepository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.task.not_found")));
        taskRepository.delete(task);
        eventPublisher.publishTaskEvent(workspaceId, "TASK_DELETED", Map.of("id", id, "userId", userId));
    }

    private Stage resolveStageOrDefault(Integer stageId, Integer workspaceId) {
        if (stageId != null) {
            return stageRepository.findByIdAndWorkspaceId(stageId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));
        }
        return stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));
    }

    private Integer resolvePositionForNewTask(Integer workspaceId, Stage stage, String priority) {
        boolean isFirstStage = stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId).stream()
                .findFirst()
                .map(first -> first.getId().equals(stage.getId()))
                .orElse(true);
        // LOW priority tasks in the first stage drop to the bottom; everything else goes to the top.
        if ("LOW".equals(priority) && isFirstStage) {
            return taskRepository.findMaxPositionByWorkspaceIdAndStageId(workspaceId, stage.getId())
                    .map(max -> max + 10)
                    .orElse(10);
        } else {
            return taskRepository.findMinPositionByWorkspaceIdAndStageId(workspaceId, stage.getId())
                    .map(min -> min - 10)
                    .orElse(-10);
        }
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
        map.put("reporterName", task.getUser().getName());
        map.put("projectId", task.getProject() != null ? task.getProject().getId() : null);
        map.put("taskTypeId", task.getTaskType() != null ? task.getTaskType().getId() : null);
        map.put("stageId", task.getStage() != null ? task.getStage().getId() : null);
        if (task.getStage() != null) {
            Stage s = task.getStage();
            Map<String, Object> stageMap = new LinkedHashMap<>();
            stageMap.put("id", s.getId());
            stageMap.put("name", s.getName());
            stageMap.put("color", s.getColor());
            stageMap.put("isFinal", Boolean.TRUE.equals(s.getIsFinal()));
            map.put("stage", stageMap);
        } else {
            map.put("stage", null);
        }
        map.put("workspaceId", task.getWorkspace().getId());
        map.put("assigneeId", task.getAssignee() != null ? task.getAssignee().getId() : null);
        map.put("assigneeName", task.getAssignee() != null ? task.getAssignee().getName() : messageService.get("task.unassigned"));
        map.put("dueDate", task.getDueDate() != null ? task.getDueDate().toString() : null);
        return map;
    }
}
