package com.dailytracker.api.repository;

import com.dailytracker.api.entity.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskTypeRepository extends JpaRepository<TaskType, Integer> {
    Optional<TaskType> findByIdAndProject_UserId(Integer id, Integer userId);
}
