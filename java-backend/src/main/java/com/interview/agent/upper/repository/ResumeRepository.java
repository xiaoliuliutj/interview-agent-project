package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<ResumeEntity, String> {
    Optional<ResumeEntity> findByFileHash(String fileHash);
}
