package com.dailytracker.api.repository;

import com.dailytracker.api.entity.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Integer> {

    List<NotificationRule> findByWorkspaceId(Integer workspaceId);

    @Query("SELECT r FROM NotificationRule r WHERE r.workspaceId = :workspaceId AND r.isActive = true " +
           "AND (r.projectId IS NULL OR r.projectId = :projectId)")
    List<NotificationRule> findActiveRulesForTask(@Param("workspaceId") Integer workspaceId,
                                                   @Param("projectId") Integer projectId);

    @Query("SELECT r FROM NotificationRule r WHERE r.workspaceId = :workspaceId AND r.isActive = true " +
           "AND r.projectId IS NULL")
    List<NotificationRule> findActiveWorkspaceScopeRules(@Param("workspaceId") Integer workspaceId);
}
