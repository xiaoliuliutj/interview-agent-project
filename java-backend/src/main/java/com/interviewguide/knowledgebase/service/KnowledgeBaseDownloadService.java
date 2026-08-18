package com.interviewguide.knowledgebase.service;

import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import com.interviewguide.utils.file.DocumentContentUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Handles the single HTTP use case that downloads one knowledge-base document. */
@Service
public class KnowledgeBaseDownloadService {
    /** Provides the owned-document lookup capability. */
    private final KnowledgeBaseLifecycleService lifecycleService;

    /** Creates the download endpoint service. */
    public KnowledgeBaseDownloadService(KnowledgeBaseLifecycleService lifecycleService) {
        // Save the internal business capability required by this endpoint.
        this.lifecycleService = lifecycleService;
    }

    /** Builds the full binary download response for one caller-owned document. */
    public ResponseEntity<byte[]> download(long id, String userId) {
        // Load the owned document before forming HTTP download metadata.
        KnowledgeBaseEntity document = lifecycleService.download(id, userId);
        // Preserve stored media type and original bytes while supporting legacy text-only rows.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(DocumentContentUtil.downloadContentType(document.getContentType())))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                        + DocumentContentUtil.downloadFilename(document.getOriginalFilename(), id) + "\"")
                .body(DocumentContentUtil.downloadBytes(document.getOriginalBytes(), document.getContent()));
    }
}
