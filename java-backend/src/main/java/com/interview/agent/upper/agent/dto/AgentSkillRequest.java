package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Java 仅代理下层的只读 Skill 目录和确定性 JD 分类能力。 */
public record AgentSkillRequest(
        @NotBlank String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        String inputText,
        @NotNull Instant timestamp) {
}
