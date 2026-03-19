package com.dailytracker.api.job;

import com.dailytracker.api.entity.NotificationSchedule;
import com.dailytracker.api.repository.NotificationScheduleRepository;
import com.dailytracker.api.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatchJob {

    private final NotificationScheduleRepository scheduleRepository;
    private final EmailService emailService;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dispatch() {
        Instant now = Instant.now();
        List<NotificationSchedule> due = scheduleRepository.findPendingDue(now);
        if (!due.isEmpty()) {
            log.info("Dispatching {} pending notification(s)", due.size());
        }
        for (NotificationSchedule schedule : due) {
            emailService.sendDueDateNotification(schedule, schedule.getTask());
        }
    }
}
