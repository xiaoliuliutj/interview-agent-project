package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that deletes one knowledge-base document. */
@Service
public class KnowledgeBaseDeleteService {
    /** Provides the document and lower-vector deletion capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the delete endpoint service. */
    public KnowledgeBaseDeleteService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Deletes the caller-owned document after its Python vectors are removed. */
    public void delete(long id, String userId) {
        // Delegate the complete delete state-machine operation.
        lifecycleService.delete(id, userId);
    }
}
