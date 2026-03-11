package com.dailytracker.api.repository;

import com.dailytracker.api.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Integer> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Integer workspaceId, Integer userId);

    boolean existsByWorkspaceIdAndUserId(Integer workspaceId, Integer userId);

    List<WorkspaceMember> findByWorkspaceId(Integer workspaceId);

    void deleteByWorkspaceIdAndUserId(Integer workspaceId, Integer userId);
}
