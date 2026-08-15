package com.interviewguide.knowledgebase.service;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.infrastructure.redis.JavaTaskStatusCache;
import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import com.interviewguide.knowledgebase.mapper.KnowledgeBaseRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persists vector-index state before publishing the same snapshot to Redis. */
@Service
public class KnowledgeBasePersistenceService {
    private final KnowledgeBaseRepository repository;
    private final JavaTaskStatusCache taskCache;

    public KnowledgeBasePersistenceService(KnowledgeBaseRepository repository, JavaTaskStatusCache taskCache) {
        this.repository = repository;
        this.taskCache = taskCache;
    }

    @Transactional
    public void markIndexed(String id, int count) {
        KnowledgeBaseEntity entity = required(id);
        entity.markVectorized(count);
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public void markIndexPending(String id) {
        KnowledgeBaseEntity entity = required(id);
        entity.markVectorPending();
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public boolean markIndexing(String id) {
        KnowledgeBaseEntity entity = required(id);
        boolean changed = entity.markVectorProcessing();
        if (changed) {
            repository.save(entity);
            cacheAfterCommit(entity);
        }
        return changed;
    }

    @Transactional
    public void markIndexFailed(String id, String message) {
        KnowledgeBaseEntity entity = required(id);
        entity.markVectorFailed(message == null ? "RAG indexing failed" : message.substring(0, Math.min(500, message.length())));
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public void markDeleting(String id) {
        KnowledgeBaseEntity entity = required(id);
        entity.markDeleting();
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    @Transactional
    public void deleteMarked(String id) {
        KnowledgeBaseEntity entity = required(id);
        if (!entity.isDeleting()) {
            throw new BusinessException("KNOWLEDGE_BASE_DELETE_STATE_INVALID", "knowledge base is not marked for deletion");
        }
        repository.delete(entity);
        afterCommit(() -> taskCache.removeKnowledgeBaseIndex(id));
    }

    @Transactional
    public void markDeleteFailed(String id, String message) {
        KnowledgeBaseEntity entity = required(id);
        entity.markDeleteFailed(message);
        repository.save(entity);
        cacheAfterCommit(entity);
    }

    private KnowledgeBaseEntity required(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "knowledge base not found"));
    }

    private void cacheAfterCommit(KnowledgeBaseEntity entity) {
        afterCommit(() -> taskCache.updateKnowledgeBaseIndex(
                entity.getId(), entity.getVectorStatus(), entity.getVectorError()));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
