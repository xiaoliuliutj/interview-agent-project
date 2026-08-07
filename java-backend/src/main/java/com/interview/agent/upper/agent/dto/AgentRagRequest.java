package com.interview.agent.upper.agent.dto;

import java.util.List;

public record AgentRagRequest(
        String apiVersion,
        String requestId,
        String runId,
        String userId,
        String sessionId,
        String operation,
        String question,
        List<String> knowledgeBaseIds,
        String useCase) {
}
