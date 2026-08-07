package com.interview.agent.upper.agent.dto;

import java.util.List;

public record AgentRagIndexRequest(
        String apiVersion,
        String requestId,
        String runId,
        String userId,
        String sessionId,
        String operation,
        String question,
        List<String> knowledgeBaseIds,
        String documentId,
        String sourceName) {
}
