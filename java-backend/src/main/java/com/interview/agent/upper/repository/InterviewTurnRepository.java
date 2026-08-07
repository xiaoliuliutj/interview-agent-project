package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.InterviewTurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurnEntity, Long> {
    boolean existsByRunId(String runId);
    List<InterviewTurnEntity> findBySessionIdOrderByCreatedAt(String sessionId);
    void deleteBySessionId(String sessionId);
}
