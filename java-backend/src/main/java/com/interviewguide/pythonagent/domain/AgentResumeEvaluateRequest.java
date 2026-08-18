package com.interviewguide.pythonagent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Java 涓婂眰鍚?Python 璇勪�?Agent 鍙戦€佺殑閫氱敤杈撳叆璇锋眰�?*/
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
