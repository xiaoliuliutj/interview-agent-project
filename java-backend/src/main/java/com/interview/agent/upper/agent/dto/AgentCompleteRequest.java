package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 关闭下层 Agent 会话的内部请求，不承载候选人回答。 */
public record AgentCompleteRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String sessionStatus,
        @Min(0) long stateVersion,
        @NotNull Instant timestamp) {
}
