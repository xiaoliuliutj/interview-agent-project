package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseIndexWorker {
    private final AgentGateway agentGateway;
    private final KnowledgeBasePersistenceService persistence;

    public KnowledgeBaseIndexWorker(AgentGateway agentGateway, KnowledgeBasePersistenceService persistence) {
        this.agentGateway = agentGateway;
        this.persistence = persistence;
    }

    @Async("interviewTaskExecutor")
    public void index(KnowledgeBaseEntity knowledgeBase) {
        try {
            AgentResponse response = agentGateway.indexRag(new AgentRagIndexRequest(
                    "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "system", "kb-" + knowledgeBase.getId(), "rag.index",
                    knowledgeBase.getContent(), List.of(knowledgeBase.getId()),
                    knowledgeBase.getId(), knowledgeBase.getOriginalFilename()));
            if (response.code() < 100 || response.code() >= 200) {
                persistence.markIndexFailed(knowledgeBase.getId(),
                        response.error() == null ? "Python RAG 索引失败" : response.error().message());
                return;
            }
            persistence.markIndexed(knowledgeBase.getId(), Integer.parseInt(response.answer()));
        } catch (RuntimeException error) {
            persistence.markIndexFailed(knowledgeBase.getId(), error.getMessage());
        }
    }
}
