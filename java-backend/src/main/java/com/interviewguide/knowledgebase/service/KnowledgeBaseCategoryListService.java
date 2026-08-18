package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.List;

/** Handles the single HTTP use case that returns the caller's document categories. */
@Service
public class KnowledgeBaseCategoryListService {
    /** Provides the owned-category query capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the category-list endpoint service. */
    public KnowledgeBaseCategoryListService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Returns distinct non-empty categories belonging to the caller. */
    public List<String> categories(String userId) {
        // Delegate ownership-aware category collection.
        return lifecycleService.categories(userId);
    }
}
