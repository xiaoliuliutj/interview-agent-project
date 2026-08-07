package com.interview.agent.upper.api.dto;

import java.time.Instant;

public record RagChatMessageView(
        long id,
        String type,
        String content,
        Instant createdAt) {
}
