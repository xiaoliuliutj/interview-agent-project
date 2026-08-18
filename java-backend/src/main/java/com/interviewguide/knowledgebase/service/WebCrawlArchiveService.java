package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.WebArchiveEntity;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that downloads a crawl provenance archive. */
@Service
public class WebCrawlArchiveService {
    /** Provides the owner-scoped preview archive workflow. */
    private final WebCrawlWorkflowService workflowService;

    /** Creates the archive-download endpoint service. */
    public WebCrawlArchiveService(WebCrawlWorkflowService workflowService) {
        // Save the complete reusable web workflow.
        this.workflowService = workflowService;
    }

    /** Returns the archive for one caller-owned crawl preview. */
    public WebArchiveEntity archive(String userId, String previewToken) {
        // Delegate preview ownership validation and archive creation.
        return workflowService.archive(userId, previewToken);
    }
}
