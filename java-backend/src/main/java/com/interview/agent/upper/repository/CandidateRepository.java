package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.CandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<CandidateEntity, String> {
}
