package com.dailytracker.api.repository;

import com.dailytracker.api.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Integer> {
    List<Project> findByUserIdOrderByNameAsc(Integer userId);
    Optional<Project> findByIdAndUserId(Integer id, Integer userId);
}
