package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.InterviewTurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurnEntity, Long> {
    Optional<InterviewTurnEntity> findByRunId(String runId);
    List<InterviewTurnEntity> findBySessionIdOrderByCreatedAt(String sessionId);
    void deleteBySessionId(String sessionId);
}
