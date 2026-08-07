package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagChatMessageRequest(
        @NotBlank @Size(max = 4000) String question) {
}
