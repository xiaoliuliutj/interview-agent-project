package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.InterviewTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewTaskRepository extends JpaRepository<InterviewTaskEntity, String> {
}
