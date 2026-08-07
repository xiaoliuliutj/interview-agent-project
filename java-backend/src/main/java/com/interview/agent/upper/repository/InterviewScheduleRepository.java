package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.InterviewScheduleEntity;
import com.interview.agent.upper.domain.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    List<InterviewScheduleEntity> findByUserIdAndStartAtBetweenOrderByStartAt(
            String userId, Instant start, Instant end);

    List<InterviewScheduleEntity> findByUserIdAndStatusOrderByStartAt(
            String userId, ScheduleStatus status);
}
