package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** Handles the single HTTP use case that searches document names. */
@Service
public class KnowledgeBaseSearchService {
    /** Provides the owned-document search capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the search endpoint service. */
    public KnowledgeBaseSearchService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Searches only document names owned by the caller. */
    public List<KnowledgeBaseResponse> search(String keyword, String userId) {
        // Delegate the ownership-aware name search.
        return lifecycleService.search(keyword, userId);
    }
}
