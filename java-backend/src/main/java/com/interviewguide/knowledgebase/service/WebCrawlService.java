package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Handles the single HTTP use case that starts one bounded web crawl. */
@Service
public class WebCrawlService {
    /** Provides the bounded crawl and preview-creation workflow. */
    private final WebCrawlWorkflowService workflowService;

    /** Creates the crawl endpoint service. */
    public WebCrawlService(WebCrawlWorkflowService workflowService) {
        // Save the complete reusable web workflow.
        this.workflowService = workflowService;
    }

    /** Starts a crawl and returns its owner-scoped preview result. */
    public Map<String, Object> crawl(String userId, String url, String topic) {
        // Delegate request construction, Python invocation and preview storage.
        return workflowService.crawl(userId, url, topic);
    }
}
