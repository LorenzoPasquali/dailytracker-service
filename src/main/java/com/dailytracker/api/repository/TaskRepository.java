package com.dailytracker.api.repository;

import com.dailytracker.api.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Integer> {
    List<Task> findByUserIdOrderByCreatedAtDesc(Integer userId);
    Optional<Task> findByIdAndUserId(Integer id, Integer userId);
}
