package com.dailytracker.api.repository;

import com.dailytracker.api.entity.WorkspaceInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, Integer> {

    Optional<WorkspaceInvite> findByToken(String token);
}
