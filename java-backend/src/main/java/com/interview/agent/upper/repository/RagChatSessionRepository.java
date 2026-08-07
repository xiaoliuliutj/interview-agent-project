package com.interview.agent.upper.repository;

import com.interview.agent.upper.domain.RagChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity, Long> {
    List<RagChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
