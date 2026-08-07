package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** Java 仅代理下层的只读 Skill 目录和确定性 JD 分类能力。 */
public record AgentSkillRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String question) {
}
