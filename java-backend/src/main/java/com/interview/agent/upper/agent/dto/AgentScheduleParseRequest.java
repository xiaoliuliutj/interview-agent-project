package com.interview.agent.upper.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** 上层发送给下层的日程文本结构化抽取请求。 */
public record AgentScheduleParseRequest(
        String apiVersion,
        @NotBlank String requestId,
        @NotBlank String runId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String operation,
        @NotBlank String inputText,
        @NotBlank String timezone) {
}
