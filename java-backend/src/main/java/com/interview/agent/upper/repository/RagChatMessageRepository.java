package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.RagChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {
    List<RagChatMessageEntity> findBySessionIdOrderByCreatedAt(Long sessionId);
    long countBySessionId(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
