package com.interview.agent.upper.api.dto;

public record KnowledgeBaseQueryResponse(
        String answer,
        long knowledgeBaseId,
        String knowledgeBaseName) {
}
