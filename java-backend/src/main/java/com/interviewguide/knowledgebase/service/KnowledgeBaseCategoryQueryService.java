package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** Handles the single HTTP use case that queries one document category. */
@Service
public class KnowledgeBaseCategoryQueryService {
    /** Provides the owned-category document query capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the category-query endpoint service. */
    public KnowledgeBaseCategoryQueryService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Returns documents owned by the caller in the requested category. */
    public List<KnowledgeBaseResponse> query(String category, String userId) {
        // Delegate the ownership-aware category lookup.
        return lifecycleService.byCategory(category, userId);
    }
}
