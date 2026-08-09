package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.ResumeAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {
    List<ResumeAnalysisEntity> findByResumeIdOrderByCreatedAtDesc(String resumeId);
    Optional<ResumeAnalysisEntity> findFirstByResumeIdOrderByCreatedAtDesc(String resumeId);
    void deleteByResumeId(String resumeId);
    List<ResumeAnalysisEntity> findByResumeIdInAndStatusIn(Collection<String> resumeIds, Collection<String> statuses);
}
