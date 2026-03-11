package com.dailytracker.api.service;

import com.dailytracker.api.entity.Workspace;
import com.dailytracker.api.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkspaceEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspaceRepository workspaceRepository;

    public void publishTaskEvent(Integer workspaceId, String eventType, Map<String, Object> payload) {
        if (!isSharedWorkspace(workspaceId)) return;
        publish(workspaceId, eventType, payload);
    }

    public void publishProjectEvent(Integer workspaceId, String eventType, Map<String, Object> payload) {
        if (!isSharedWorkspace(workspaceId)) return;
        publish(workspaceId, eventType, payload);
    }

    public void publishTaskTypeEvent(Integer workspaceId, String eventType, Map<String, Object> payload) {
        if (!isSharedWorkspace(workspaceId)) return;
        publish(workspaceId, eventType, payload);
    }

    public void publishMemberEvent(Integer workspaceId, String eventType, Map<String, Object> payload) {
        if (!isSharedWorkspace(workspaceId)) return;
        publish(workspaceId, eventType, payload);
    }

    public void publishWorkspaceEvent(Integer workspaceId, String eventType, Map<String, Object> payload) {
        if (!isSharedWorkspace(workspaceId)) return;
        publish(workspaceId, eventType, payload);
    }

    public void publishUserEventForMemberWorkspaces(Integer userId, String eventType, Map<String, Object> payload) {
        for (Workspace workspace : workspaceRepository.findAllByMemberUserId(userId)) {
            if (Boolean.TRUE.equals(workspace.getIsPersonal())) continue;
            publish(workspace.getId(), eventType, payload);
        }
    }

    private void publish(Integer workspaceId, String eventType, Map<String, Object> payload) {
        Map<String, Object> event = Map.of("type", eventType, "payload", payload);
        messagingTemplate.convertAndSend("/topic/workspace/" + workspaceId, event);
    }

    private boolean isSharedWorkspace(Integer workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .map(w -> !Boolean.TRUE.equals(w.getIsPersonal()))
                .orElse(false);
    }
}
