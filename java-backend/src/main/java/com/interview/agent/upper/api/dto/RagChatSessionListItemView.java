package com.interview.agent.upper.api.dto;

import java.time.Instant;
import java.util.List;

public record RagChatSessionListItemView(
        long id,
        String title,
        long messageCount,
        List<String> knowledgeBaseNames,
        Instant updatedAt,
        boolean isPinned) {
}
