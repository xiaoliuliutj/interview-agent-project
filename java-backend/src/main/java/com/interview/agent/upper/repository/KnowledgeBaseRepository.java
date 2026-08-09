package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    List<KnowledgeBaseEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<KnowledgeBaseEntity> findByOwnerIdAndCategory(String ownerId, String category);
    List<KnowledgeBaseEntity> findByOwnerIdAndNameContainingIgnoreCase(String ownerId, String keyword);
    List<KnowledgeBaseEntity> findByOwnerIdAndVectorStatus(String ownerId, String vectorStatus);
    List<KnowledgeBaseEntity> findByIdInAndVectorStatus(Iterable<String> ids, String vectorStatus);
}
