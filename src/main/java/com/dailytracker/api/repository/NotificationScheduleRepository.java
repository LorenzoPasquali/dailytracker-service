package com.dailytracker.api.repository;

import com.dailytracker.api.entity.NotificationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationScheduleRepository extends JpaRepository<NotificationSchedule, Integer> {

    @Modifying
    @Query("DELETE FROM NotificationSchedule s WHERE s.taskId = :taskId AND s.status = 'PENDING'")
    void deletePendingByTaskId(@Param("taskId") Integer taskId);

    @Modifying
    @Query("DELETE FROM NotificationSchedule s WHERE s.ruleId = :ruleId AND s.status = 'PENDING'")
    void deletePendingByRuleId(@Param("ruleId") Integer ruleId);

    @Query(value = """
            SELECT * FROM "NotificationSchedule"
            WHERE status = 'PENDING'
              AND (("retryCount" = 0 AND "scheduledAt" <= :now)
                OR ("retryCount" > 0 AND "nextRetryAt" <= :now))
            LIMIT 100
            """, nativeQuery = true)
    List<NotificationSchedule> findPendingDue(@Param("now") Instant now);
}
