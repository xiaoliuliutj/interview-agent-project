package com.interviewguide.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Handles the single HTTP use case that imports selected crawl-preview pages. */
@Service
public class WebCrawlImportService {
    /** Provides the preview ownership, selection and document-import workflow. */
    private final WebCrawlWorkflowService workflowService;

    /** Creates the selected-page import endpoint service. */
    public WebCrawlImportService(WebCrawlWorkflowService workflowService) {
        // Save the complete reusable web workflow.
        this.workflowService = workflowService;
    }

    /** Imports the selected pages and formats the endpoint response contract. */
    public Map<String, Object> importCrawl(String userId, String previewToken, List<String> pageIds,
                                           String category) {
        // Execute the complete owner-scoped import workflow once.
        List<WebCrawlPreviewService.ImportedPage> imported =
                workflowService.importCrawl(userId, previewToken, pageIds, category);
        // Return only the import result contract for this endpoint.
        return Map.of("importedCount", imported.size(), "knowledgeBases", imported);
    }
}
