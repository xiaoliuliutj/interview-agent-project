package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 原前端知识库问答页面创建会话时使用的业务请求。 */
public record CreateRagChatSessionRequest(
        @NotEmpty List<@NotNull Long> knowledgeBaseIds,
        @Size(max = 120) String title) {
}
