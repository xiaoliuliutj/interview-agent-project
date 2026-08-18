package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that schedules one document for vector rebuilding. */
@Service
public class KnowledgeBaseRevectorizeService {
    /** Provides the persistent state reset and asynchronous-index capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the revectorize endpoint service. */
    public KnowledgeBaseRevectorizeService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Resets the caller-owned document to pending and queues its index task. */
    public void revectorize(long id, String userId) {
        // Delegate the complete vector lifecycle reset.
        lifecycleService.revectorize(id, userId);
    }
}
