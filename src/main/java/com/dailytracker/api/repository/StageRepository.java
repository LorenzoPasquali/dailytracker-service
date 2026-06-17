package com.dailytracker.api.repository;

import com.dailytracker.api.entity.Stage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageRepository extends JpaRepository<Stage, Integer> {
    List<Stage> findByWorkspaceIdOrderByPositionAsc(Integer workspaceId);
    Optional<Stage> findByIdAndWorkspaceId(Integer id, Integer workspaceId);
    long countByWorkspaceId(Integer workspaceId);
}
