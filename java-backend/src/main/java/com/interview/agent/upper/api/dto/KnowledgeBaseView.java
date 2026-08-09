package com.interview.agent.upper.api.dto;

import java.time.Instant;

public record KnowledgeBaseView(
        long id,
        String name,
        String category,
        String originalFilename,
        long fileSize,
        String contentType,
        Instant uploadedAt,
        Instant updatedAt,
        String vectorStatus,
        String vectorError,
        int chunkCount) {
}
