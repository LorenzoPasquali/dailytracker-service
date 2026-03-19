package com.dailytracker.api.service;

import com.dailytracker.api.entity.NotificationRule;
import com.dailytracker.api.entity.NotificationSchedule;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.repository.NotificationRuleRepository;
import com.dailytracker.api.repository.NotificationScheduleRepository;
import com.dailytracker.api.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationScheduleService {

    private final NotificationScheduleRepository scheduleRepository;
    private final NotificationRuleRepository ruleRepository;
    private final TaskRepository taskRepository;

    @Transactional
    public void recomputeForTask(Task task) {
        scheduleRepository.deletePendingByTaskId(task.getId());

        if (task.getDueDate() == null) {
            return;
        }

        List<NotificationRule> rules = ruleRepository.findActiveRulesForTask(
                task.getWorkspaceId(), task.getProjectId());

        Instant now = Instant.now();
        for (NotificationRule rule : rules) {
            for (var offset : rule.getOffsets()) {
                Instant scheduledAt = task.getDueDate().minusSeconds((long) offset.getMinutes() * 60);
                if (scheduledAt.isAfter(now)) {
                    for (var recipient : rule.getRecipients()) {
                        scheduleRepository.save(NotificationSchedule.builder()
                                .task(task)
                                .rule(rule)
                                .recipientEmail(recipient.getEmail())
                                .offsetMinutes(offset.getMinutes())
                                .scheduledAt(scheduledAt)
                                .build());
                    }
                }
            }
        }

        log.debug("Recomputed schedules for task id={}", task.getId());
    }

    @Transactional
    public void recomputeForRule(NotificationRule rule) {
        scheduleRepository.deletePendingByRuleId(rule.getId());

        if (!Boolean.TRUE.equals(rule.getIsActive())) {
            return;
        }

        Instant now = Instant.now();
        List<Task> tasks = rule.getProjectId() != null
                ? taskRepository.findByWorkspaceIdAndProjectIdAndDueDateIsNotNull(rule.getWorkspaceId(), rule.getProjectId())
                : taskRepository.findByWorkspaceIdAndDueDateIsNotNull(rule.getWorkspaceId());

        for (Task task : tasks) {
            for (var offset : rule.getOffsets()) {
                Instant scheduledAt = task.getDueDate().minusSeconds((long) offset.getMinutes() * 60);
                if (scheduledAt.isAfter(now)) {
                    for (var recipient : rule.getRecipients()) {
                        scheduleRepository.save(NotificationSchedule.builder()
                                .task(task)
                                .rule(rule)
                                .recipientEmail(recipient.getEmail())
                                .offsetMinutes(offset.getMinutes())
                                .scheduledAt(scheduledAt)
                                .build());
                    }
                }
            }
        }

        log.debug("Recomputed schedules for rule id={}", rule.getId());
    }
}
