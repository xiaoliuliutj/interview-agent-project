package com.interview.agent.upper.service;

import com.interview.agent.upper.domain.KnowledgeBaseEntity;
import com.interview.agent.upper.repository.KnowledgeBaseRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBasePersistenceService {
    private final KnowledgeBaseRepository repository;

    public KnowledgeBasePersistenceService(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void markIndexed(String id, int count) {
        required(id).markVectorized(count);
    }

    @Transactional
    public void markIndexPending(String id) {
        required(id).markVectorPending();
    }

    @Transactional
    public boolean markIndexing(String id) {
        return required(id).markVectorProcessing();
    }

    @Transactional
    public void markIndexFailed(String id, String message) {
        required(id).markVectorFailed(message == null ? "RAG 索引失败" : message.substring(0, Math.min(500, message.length())));
    }

    @Transactional
    public void markDeleting(String id) {
        required(id).markDeleting();
    }

    @Transactional
    public void deleteMarked(String id) {
        KnowledgeBaseEntity entity = required(id);
        if (!entity.isDeleting()) {
            throw new BusinessException("KNOWLEDGE_BASE_DELETE_STATE_INVALID",
                    "knowledge base is not marked for deletion");
        }
        repository.delete(entity);
    }

    @Transactional
    public void markDeleteFailed(String id, String message) {
        required(id).markDeleteFailed(message);
    }

    private KnowledgeBaseEntity required(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在"));
    }
}
