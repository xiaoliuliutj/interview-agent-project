package com.interview.agent.upper.api.dto;

import java.util.List;

public record KnowledgeBaseQueryRequest(List<Long> knowledgeBaseIds, String question) {
}
