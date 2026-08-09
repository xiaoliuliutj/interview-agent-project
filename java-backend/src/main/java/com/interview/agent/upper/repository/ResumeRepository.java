package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface ResumeRepository extends JpaRepository<ResumeEntity, String> {
    Optional<ResumeEntity> findFirstByCandidateIdAndFileHash(String candidateId, String fileHash);
    List<ResumeEntity> findByCandidateId(String candidateId);
    Optional<ResumeEntity> findFirstByCandidateIdOrderByVersionDesc(String candidateId);
}
