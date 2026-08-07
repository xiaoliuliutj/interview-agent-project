package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.InterviewSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, String> {
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<InterviewSessionEntity> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            String userId, String resumeId, List<InterviewSessionStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InterviewSessionEntity s where s.id = :id")
    Optional<InterviewSessionEntity> findByIdForUpdate(@Param("id") String id);
}
