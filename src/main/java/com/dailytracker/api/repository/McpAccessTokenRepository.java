package com.dailytracker.api.repository;

import com.dailytracker.api.entity.McpAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Integer> {

    Optional<McpAccessToken> findByTokenHash(String tokenHash);

    List<McpAccessToken> findByUser_IdAndRevokedFalseOrderByCreatedAtDesc(Integer userId);

    Optional<McpAccessToken> findByIdAndUser_Id(Integer id, Integer userId);
}
