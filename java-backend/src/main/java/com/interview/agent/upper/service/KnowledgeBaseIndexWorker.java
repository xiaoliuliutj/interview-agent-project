package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.AgentGatewayException;
import com.interview.agent.upper.agent.dto.AgentRagDeleteRequest;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.config.RabbitTaskConfiguration;
import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
        KnowledgeBaseEntity knowledgeBase = repository.findById(knowledgeBaseId).orElse(null);
        // A queue message can arrive after its source document was deleted.
        if (knowledgeBase == null) {
            return;
        }
        if (!userId.equals(knowledgeBase.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "knowledge base does not belong to current user");
        }
        // 删除流程已经开始时，队列中的旧索引任务不得重新写入向量。
        if (knowledgeBase.hasDeletionRequest()) {
            return;
        }
        if (!persistence.markIndexing(knowledgeBase.getId())) {
            return;
        }
        try {
            AgentResponse response = agentGateway.indexRag(new AgentRagIndexRequest(
                    "v1", UUID.randomUUID().toString(), "rag-index-" + knowledgeBase.getId(),
                    userId, "kb-" + knowledgeBase.getId(), "rag.index",
                    knowledgeBase.getContent(), List.of(knowledgeBase.getId()),
                    knowledgeBase.getId(), knowledgeBase.getOriginalFilename(), Instant.now()));
            if (response == null || response.code() < 100 || response.code() >= 200) {
                String message = response != null && response.error() != null
                        ? response.error().message() : "lower RAG indexing failed";
                persistence.markIndexFailed(knowledgeBase.getId(), message);
                if (response != null && response.retryable()) {
                    throw new AgentGatewayException(message, null, true);
                }
                return;
            }
            KnowledgeBaseEntity latest = repository.findById(knowledgeBase.getId()).orElse(null);
            if (latest == null || latest.hasDeletionRequest()) {
                // 删除在嵌入期间开始或完成：清理本次迟到写入的向量，且绝不回写索引成功状态。
                AgentResponse deletion = agentGateway.deleteRag(new AgentRagDeleteRequest(
                        "v1", UUID.randomUUID().toString(), "rag-delete-" + knowledgeBase.getId(),
                        userId, "kb-delete-" + knowledgeBase.getId(), "rag.delete", knowledgeBase.getId(), Instant.now()));
                if (deletion == null || deletion.code() < 100 || deletion.code() >= 200) {
                    throw new BusinessException("KNOWLEDGE_BASE_VECTOR_DELETE_FAILED",
                            "late vector cleanup failed after knowledge-base deletion");
                }
                return;
            }
            persistence.markIndexed(latest.getId(), Integer.parseInt(response.answer()));
        } catch (RuntimeException error) {
            KnowledgeBaseEntity latest = repository.findById(knowledgeBaseId).orElse(null);
            // 删除状态只能由删除流程推进，索引失败不得把 DELETING 覆盖成 FAILED。
            if (latest != null && !latest.hasDeletionRequest()) {
                persistence.markIndexFailed(latest.getId(), error.getMessage());
            }
            // Only temporary lower-service failures are allowed to reach the
            // Rabbit listener retry policy.  Validation, contract and business
            // errors have already been persisted as FAILED and must be acked.
            if (error instanceof BusinessException
                    || error instanceof AgentGatewayException gatewayError && !gatewayError.retryable()) {
                return;
            }
            throw error;
        }
    }
}
