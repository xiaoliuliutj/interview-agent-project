package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Java 上层向 Python 评价 Agent 发送的通用输入请求。 */
public record AgentResumeEvaluateRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String subjectType,
        @NotBlank String subjectId,
        @NotBlank String candidateId,
        @NotBlank String inputText,
        @NotBlank String targetRole,
        @NotNull Instant timestamp) {
}
