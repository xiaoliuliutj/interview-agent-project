package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/** Handles the single HTTP use case that uploads one knowledge-base document. */
@Service
public class KnowledgeBaseUploadService {
    /** Reuses the complete document-lifecycle capability without exposing it to controllers. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the endpoint service with the knowledge-base lifecycle dependency. */
    public KnowledgeBaseUploadService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability used by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Validates optional provenance time and stores the uploaded document. */
    public Map<String, Object> upload(MultipartFile file, String name, String category, String userId,
                                      String sourceUrl, String sourceTitle, String sourceFetchedAt,
                                      String sourceHash) throws IOException {
        // Keep source provenance optional because direct uploads do not provide it.
        Instant fetchedAt = null;
        // Parse a provided ISO-8601 timestamp before it reaches persistent state.
        if (sourceFetchedAt != null && !sourceFetchedAt.isBlank()) {
            // Report malformed provenance instead of silently storing an invalid timestamp.
            fetchedAt = Instant.parse(sourceFetchedAt);
        }
        // Execute the complete upload and asynchronous-index publication flow.
        KnowledgeBaseResponse response = lifecycleService.upload(
                file, name, category, userId, sourceUrl, sourceTitle, fetchedAt, sourceHash);
        // Return only the response contract required by the upload endpoint.
        return Map.of("knowledgeBase", Map.of(
                "id", response.id(),
                "name", response.name(),
                "category", response.category() == null ? "" : response.category(),
                "fileSize", response.fileSize(),
                "contentLength", file.getSize()));
    }
}
