package com.dailytracker.api.service;

import com.dailytracker.api.dto.request.NotificationRuleRequest;
import com.dailytracker.api.entity.*;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.exception.ResourceNotFoundException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationRuleService {

    private final NotificationRuleRepository ruleRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final NotificationScheduleService scheduleService;
    private final MessageService messageService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRules(Integer workspaceId, Integer userId) {
        workspaceService.assertMember(workspaceId, userId);
        return ruleRepository.findByWorkspaceId(workspaceId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> createRule(NotificationRuleRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertCreator(workspaceId, userId);
        validateRequest(request);

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.workspace.not_found")));

        User createdBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.user.not_found")));

        NotificationRule rule = NotificationRule.builder()
                .workspace(workspace)
                .createdBy(createdBy)
                .name(request.name())
                .isActive(request.isActive() == null || request.isActive())
                .build();

        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));
            rule.setProject(project);
        }

        for (String email : request.emails()) {
            rule.getRecipients().add(NotificationRecipient.builder().rule(rule).email(email.trim().toLowerCase()).build());
        }
        for (Integer minutes : request.offsets()) {
            rule.getOffsets().add(NotificationOffset.builder().rule(rule).minutes(minutes).build());
        }

        rule = ruleRepository.saveAndFlush(rule);
        scheduleService.recomputeForRule(rule);
        return toResponse(rule);
    }

    @Transactional
    public Map<String, Object> updateRule(Integer ruleId, NotificationRuleRequest request, Integer userId, Integer workspaceId) {
        workspaceService.assertCreator(workspaceId, userId);
        validateRequest(request);

        NotificationRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification rule not found"));

        rule.setName(request.name());
        if (request.isActive() != null) {
            rule.setIsActive(request.isActive());
        }

        if (request.projectId() != null) {
            Project project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.get("error.project.not_found")));
            rule.setProject(project);
        } else {
            rule.setProject(null);
        }

        // Flush deletes first to avoid UNIQUE constraint violations (Hibernate's default
        // action order is INSERT → DELETE, so clearing + re-adding the same values in
        // one flush causes constraint errors).
        rule.getRecipients().clear();
        rule.getOffsets().clear();
        ruleRepository.saveAndFlush(rule);

        for (String email : request.emails()) {
            rule.getRecipients().add(NotificationRecipient.builder().rule(rule).email(email.trim().toLowerCase()).build());
        }
        for (Integer minutes : request.offsets()) {
            rule.getOffsets().add(NotificationOffset.builder().rule(rule).minutes(minutes).build());
        }

        rule = ruleRepository.saveAndFlush(rule);
        scheduleService.recomputeForRule(rule);
        return toResponse(rule);
    }

    @Transactional
    public void deleteRule(Integer ruleId, Integer userId, Integer workspaceId) {
        workspaceService.assertCreator(workspaceId, userId);
        NotificationRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification rule not found"));
        ruleRepository.delete(rule);
    }

    private void validateRequest(NotificationRuleRequest request) {
        if (request.emails() != null && request.emails().size() > 10) {
            throw new BadRequestException("Maximum 10 recipient emails per rule");
        }
    }

    private Map<String, Object> toResponse(NotificationRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.getId());
        map.put("name", rule.getName());
        map.put("workspaceId", rule.getWorkspaceId());
        map.put("projectId", rule.getProjectId());
        map.put("projectName", rule.getProject() != null ? rule.getProject().getName() : null);
        map.put("isActive", rule.getIsActive());
        map.put("recipients", rule.getRecipients().stream().map(NotificationRecipient::getEmail).toList());
        map.put("offsets", rule.getOffsets().stream().map(NotificationOffset::getMinutes).toList());
        map.put("createdAt", rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null);
        return map;
    }
}
