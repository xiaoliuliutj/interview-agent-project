package com.interview.agent.upper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagChatTitleRequest(
        @NotBlank @Size(max = 120) String title) {
}
