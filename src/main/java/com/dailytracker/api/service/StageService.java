package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.StageRequest;
import com.dailytracker.api.entity.Stage;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.Workspace;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.StageRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StageService {

    private final StageRepository stageRepository;
    private final TaskRepository taskRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceEventPublisher eventPublisher;
    private final MessageService messageService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);
        return stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(StageRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.workspace.not_found")));

        int nextPosition = stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId)
                .stream()
                .mapToInt(Stage::getPosition)
                .max()
                .orElse(-1) + 1;

        Stage stage = Stage.builder()
                .name(request.name())
                .color(request.color() != null && !request.color().isBlank() ? request.color() : "#6b7280")
                .position(nextPosition)
                .isFinal(Boolean.TRUE.equals(request.isFinal()))
                .workspace(workspace)
                .build();

        stage = stageRepository.saveAndFlush(stage);
        Map<String, Object> response = toResponse(stage);
        eventPublisher.publishStageEvent(workspaceId, "STAGE_CREATED", response);
        return response;
    }

    @Transactional
    public Map<String, Object> update(Integer id, StageRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        Stage stage = stageRepository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));

        String oldName = stage.getName();
        stage.setName(request.name());
        if (request.color() != null && !request.color().isBlank()) {
            stage.setColor(request.color());
        }
        if (request.isFinal() != null) {
            stage.setIsFinal(request.isFinal());
        }
        stage = stageRepository.saveAndFlush(stage);

        // Keep the denormalized Task.status label in sync when the stage is renamed.
        if (!oldName.equals(stage.getName())) {
            for (Task task : taskRepository.findByStageId(stage.getId())) {
                task.setStatus(stage.getName());
                taskRepository.save(task);
            }
        }

        Map<String, Object> response = toResponse(stage);
        eventPublisher.publishStageEvent(workspaceId, "STAGE_UPDATED", response);
        return response;
    }

    @Transactional
    public void reorder(List<Map<String, Object>> items, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);
        for (Map<String, Object> item : items) {
            Integer id = (Integer) item.get("id");
            Integer position = (Integer) item.get("position");
            stageRepository.findByIdAndWorkspaceId(id, workspaceId)
                    .ifPresent(stage -> {
                        stage.setPosition(position);
                        stageRepository.save(stage);
                    });
        }
        eventPublisher.publishStageEvent(workspaceId, "STAGE_REORDERED", Map.of("items", items));
    }

    @Transactional
    public void delete(Integer id, Integer targetStageId, Integer userId, Integer workspaceId) {
        workspaceService.assertMember(workspaceId, userId);

        Stage stage = stageRepository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));

        if (stageRepository.countByWorkspaceId(workspaceId) <= 1) {
            throw new BadRequestException(messageService.get("error.stage.last"));
        }

        List<Task> tasks = taskRepository.findByStageId(id);
        if (!tasks.isEmpty()) {
            if (targetStageId == null) {
                throw new BadRequestException(messageService.get("error.stage.target_required"));
            }
            Stage target = stageRepository.findByIdAndWorkspaceId(targetStageId, workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.stage.not_found")));
            for (Task task : tasks) {
                task.setStage(target);
                task.setStatus(target.getName());
                task.setPosition(null);
                taskRepository.save(task);
            }
        }

        stageRepository.delete(stage);
        eventPublisher.publishStageEvent(workspaceId, "STAGE_DELETED",
                Map.of("id", id, "targetStageId", targetStageId != null ? targetStageId : -1));
    }

    private Map<String, Object> toResponse(Stage stage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", stage.getId());
        map.put("name", stage.getName());
        map.put("color", stage.getColor());
        map.put("position", stage.getPosition());
        map.put("isFinal", Boolean.TRUE.equals(stage.getIsFinal()));
        map.put("workspaceId", stage.getWorkspaceId());
        return map;
    }
}
