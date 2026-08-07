package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.JobDescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDescriptionRepository extends JpaRepository<JobDescriptionEntity, String> {
}
