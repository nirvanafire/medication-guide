package com.medication.repository;

import com.medication.entity.QueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {

    List<QueryLog> findBySessionId(String sessionId);

    List<QueryLog> findByUserId(String userId);

    @Query("SELECT q FROM QueryLog q WHERE q.createdAt BETWEEN :start AND :end ORDER BY q.createdAt DESC")
    List<QueryLog> findByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT AVG(q.latencyMs) FROM QueryLog q WHERE q.createdAt > :since")
    Double getAverageLatency(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(q) FROM QueryLog q WHERE q.hallucinationPassed = true AND q.createdAt > :since")
    Long countPassedHallucination(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(q) FROM QueryLog q WHERE q.cacheHit = true AND q.createdAt > :since")
    Long countCacheHits(@Param("since") LocalDateTime since);
}