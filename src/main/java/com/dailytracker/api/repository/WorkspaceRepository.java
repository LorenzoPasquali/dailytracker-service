package com.dailytracker.api.repository;

import com.dailytracker.api.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Integer> {

    Optional<Workspace> findByCreatorIdAndIsPersonalTrue(Integer creatorId);

    @Query("""
            SELECT w FROM Workspace w
            WHERE w.id IN (
                SELECT m.workspaceId FROM WorkspaceMember m WHERE m.userId = :userId
            )
            ORDER BY w.isPersonal DESC, w.createdAt ASC
            """)
    List<Workspace> findAllByMemberUserId(@Param("userId") Integer userId);
}
