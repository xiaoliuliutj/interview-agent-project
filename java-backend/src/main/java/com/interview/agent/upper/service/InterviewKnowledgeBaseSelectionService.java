package com.interview.agent.upper.service;

import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/** Resolves the real, index-ready knowledge-base IDs for one interview session. */
@Service
public class InterviewKnowledgeBaseSelectionService {
    public record Selection(List<String> systemKnowledgeBaseIds, List<String> userKnowledgeBaseIds) {}

    private final KnowledgeBaseRepository repository;
    private final List<String> configuredSystemIds;

    public InterviewKnowledgeBaseSelectionService(
            KnowledgeBaseRepository repository,
            @Value("${agent.system-knowledge-base-ids:}") String configuredSystemIds) {
        this.repository = repository;
        this.configuredSystemIds = Arrays.stream(configuredSystemIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    public Selection selectForUser(String userId) {
        if (configuredSystemIds.isEmpty()) {
            throw new BusinessException("SYSTEM_KNOWLEDGE_BASE_CONFIG_REQUIRED",
                    "system knowledge base IDs must be configured before starting an interview");
        }
        List<String> readySystemIds = repository
                .findByIdInAndVectorStatus(configuredSystemIds, "COMPLETED")
                .stream().map(KnowledgeBaseEntity::getId).toList();
        if (readySystemIds.size() != configuredSystemIds.size()) {
            throw new BusinessException("SYSTEM_KNOWLEDGE_BASE_NOT_READY",
                    "configured system knowledge bases are not fully indexed");
        }
        List<String> readyUserIds = repository.findByOwnerIdAndVectorStatus(userId, "COMPLETED")
                .stream().map(KnowledgeBaseEntity::getId).toList();
        return new Selection(readySystemIds, readyUserIds);
    }
}
