package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.CandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<CandidateEntity, String> {
    Optional<CandidateEntity> findByUserId(String userId);
}
