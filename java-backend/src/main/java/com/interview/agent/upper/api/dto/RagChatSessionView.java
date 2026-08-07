package com.interview.agent.upper.api.dto;

import java.time.Instant;
import java.util.List;

public record RagChatSessionView(
        long id,
        String title,
        List<Long> knowledgeBaseIds,
        Instant createdAt) {
}
