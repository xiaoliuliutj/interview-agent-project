package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RagChatKnowledgeBasesRequest(
        @NotEmpty List<@NotNull Long> knowledgeBaseIds) {
}
