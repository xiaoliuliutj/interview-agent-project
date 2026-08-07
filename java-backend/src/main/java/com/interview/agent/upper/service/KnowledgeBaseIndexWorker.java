package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import com.interview.agent.upper.config.RabbitTaskConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseIndexWorker {
    private final AgentGateway agentGateway;
    private final KnowledgeBasePersistenceService persistence;
    private final KnowledgeBaseRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public KnowledgeBaseIndexWorker(
            AgentGateway agentGateway,
            KnowledgeBasePersistenceService persistence,
            KnowledgeBaseRepository repository,
            RabbitTemplate rabbitTemplate) {
        this.agentGateway = agentGateway;
        this.persistence = persistence;
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void index(String knowledgeBaseId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX, knowledgeBaseId, userId));
    }

    public void process(String knowledgeBaseId, String userId) {
        KnowledgeBaseEntity knowledgeBase = repository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
        if (!userId.equals(knowledgeBase.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "无权索引该知识库");
        }
        try {
            AgentResponse response = agentGateway.indexRag(new AgentRagIndexRequest(
                    "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    userId, "kb-" + knowledgeBase.getId(), "rag.index",
                    knowledgeBase.getContent(), List.of(knowledgeBase.getId()),
                    knowledgeBase.getId(), knowledgeBase.getOriginalFilename()));
            if (response.code() < 100 || response.code() >= 200) {
                String message = response.error() == null ? "Python RAG 索引失败" : response.error().message();
                persistence.markIndexFailed(knowledgeBase.getId(), message);
                throw new BusinessException("KNOWLEDGE_BASE_INDEX_AGENT_FAILED", message);
            }
            persistence.markIndexed(knowledgeBase.getId(), Integer.parseInt(response.answer()));
        } catch (RuntimeException error) {
            persistence.markIndexFailed(knowledgeBase.getId(), error.getMessage());
            throw error;
        }
    }
}
