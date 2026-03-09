package com.dailytracker.api.repository;

import com.dailytracker.api.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Integer> {

    // Priority-aware ordering:
    // - Explicitly positioned tasks sort by their position value
    // - Null-position tasks use a virtual value via COALESCE:
    //     HIGH  → -999999999 (appears before any explicitly positioned task)
    //     MEDIUM → 999999998
    //     LOW   → 999999999
    @Query(value = """
            SELECT * FROM public."Task" WHERE "userId" = :userId
            ORDER BY COALESCE(position, CASE priority
                WHEN 'HIGH'   THEN -999999999
                WHEN 'MEDIUM' THEN  999999998
                WHEN 'LOW'    THEN  999999999
                ELSE 999999998 END) ASC, "createdAt" DESC
            """, nativeQuery = true)
    List<Task> findByUserIdOrdered(@Param("userId") Integer userId);

    Optional<Task> findByIdAndUserId(Integer id, Integer userId);

    @Query("SELECT MIN(t.position) FROM Task t WHERE t.userId = :userId AND t.status = :status AND t.position IS NOT NULL")
    Optional<Integer> findMinPositionByUserIdAndStatus(@Param("userId") Integer userId, @Param("status") String status);

    @Query("SELECT MAX(t.position) FROM Task t WHERE t.userId = :userId AND t.status = :status AND t.position IS NOT NULL")
    Optional<Integer> findMaxPositionByUserIdAndStatus(@Param("userId") Integer userId, @Param("status") String status);
}
