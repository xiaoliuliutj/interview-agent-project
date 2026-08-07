package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    List<KnowledgeBaseEntity> findByCategory(String category);
    List<KnowledgeBaseEntity> findByCategoryIsNull();
    List<KnowledgeBaseEntity> findByNameContainingIgnoreCase(String keyword);
}
