package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that changes one document category. */
@Service
public class KnowledgeBaseCategoryUpdateService {
    /** Provides the owned-document category mutation capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the category-update endpoint service. */
    public KnowledgeBaseCategoryUpdateService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Applies the requested category change to the caller-owned document. */
    public void update(long id, String category, String userId) {
        // Delegate the validated persistent mutation.
        lifecycleService.updateCategory(id, category, userId);
    }
}
