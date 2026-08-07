package com.interview.agent.upper.api.dto;

import java.time.Instant;
import java.util.List;

public record RagChatSessionDetailView(
        long id,
        String title,
        List<KnowledgeBaseView> knowledgeBases,
        List<RagChatMessageView> messages,
        Instant createdAt,
        Instant updatedAt) {
}
