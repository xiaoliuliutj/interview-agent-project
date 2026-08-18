package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Handles the single HTTP use case that returns knowledge-base status totals. */
@Service
public class KnowledgeBaseStatsService {
    /** Provides the list-consistent statistics capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the statistics endpoint service. */
    public KnowledgeBaseStatsService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Calculates all status totals from the caller-visible document set. */
    public Map<String, Object> stats(String userId) {
        // Delegate the complete list-consistent statistics calculation.
        return lifecycleService.stats(userId);
    }
}
