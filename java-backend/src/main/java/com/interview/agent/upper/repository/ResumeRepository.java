package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<ResumeEntity, String> {
}
