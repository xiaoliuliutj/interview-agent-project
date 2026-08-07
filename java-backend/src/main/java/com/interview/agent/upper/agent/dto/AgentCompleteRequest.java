package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** 关闭下层 Agent 会话的内部请求，不承载候选人回答。 */
public record AgentCompleteRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        String operation) {
    public AgentCompleteRequest(
            String apiVersion, String requestId, String runId, String userId, String sessionId) {
        this(apiVersion, requestId, runId, userId, sessionId, "agent.session.complete");
    }
}
