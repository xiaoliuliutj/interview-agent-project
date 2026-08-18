package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Handles the single HTTP use case that fetches one public web page. */
@Service
public class WebFetchService {
    /** Provides the bounded Java-to-Python web-fetch workflow. */
    private final WebCrawlWorkflowService workflowService;

    /** Creates the fetch endpoint service. */
    public WebFetchService(WebCrawlWorkflowService workflowService) {
        // Save the complete reusable web workflow.
        this.workflowService = workflowService;
    }

    /** Fetches one user-requested page through the Python adapter. */
    public Map<String, Object> fetch(String userId, String url) {
        // Delegate request construction, identity validation and response validation.
        return workflowService.fetch(userId, url);
    }
}
