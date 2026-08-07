package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Java 上层向 Python 评价 Agent 发送的通用输入请求。 */
public record AgentResumeEvaluateRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        String operation,
        String subjectType,
        @NotBlank String subjectId,
        @NotBlank String inputText,
        @NotBlank String targetRole,
        List<String> knowledgeBaseIds) {
    public AgentResumeEvaluateRequest(
            String apiVersion,
            String requestId,
            String runId,
            String userId,
            String sessionId,
            String subjectId,
            String inputText,
            String targetRole,
            List<String> knowledgeBaseIds) {
        this(apiVersion, requestId, runId, userId, sessionId,
                "agent.resume.evaluate", "RESUME", subjectId, inputText,
                targetRole, knowledgeBaseIds);
    }
}
