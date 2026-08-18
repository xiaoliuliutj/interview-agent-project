package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** Handles the single HTTP use case that lists knowledge-base documents. */
@Service
public class KnowledgeBaseListService {
    /** Provides the complete owned-document query capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the list endpoint service. */
    public KnowledgeBaseListService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Lists only the caller-owned documents using the requested public filters. */
    public List<KnowledgeBaseResponse> list(String userId, String sortBy, String vectorStatus) {
        // Delegate the complete query, authorization, filtering and response conversion flow.
        return lifecycleService.list(userId, sortBy, vectorStatus);
    }
}
