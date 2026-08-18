package com.interviewguide.knowledgebase.controller;

import com.interviewguide.common.web.ApiResult;
import com.interviewguide.knowledgebase.domain.KnowledgeBaseResponse;
import com.interviewguide.knowledgebase.service.KnowledgeBaseCategoryListService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseCategoryQueryService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseCategoryUpdateService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseDeleteService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseDownloadService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseListService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseRevectorizeService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseSearchService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseStatsService;
import com.interviewguide.knowledgebase.service.KnowledgeBaseUploadService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledgebase")
/** HTTP entry point for knowledge-base document and vector-index management. */
public class KnowledgeBaseController {
    /** Handles the document upload endpoint. */
    private final KnowledgeBaseUploadService uploadService;
    /** Handles the document list endpoint. */
    private final KnowledgeBaseListService listService;
    /** Handles the original-file download endpoint. */
    private final KnowledgeBaseDownloadService downloadService;
    /** Handles the document deletion endpoint. */
    private final KnowledgeBaseDeleteService deleteService;
    /** Handles the category-list endpoint. */
    private final KnowledgeBaseCategoryListService categoryListService;
    /** Handles the category query endpoint. */
    private final KnowledgeBaseCategoryQueryService categoryQueryService;
    /** Handles the category update endpoint. */
    private final KnowledgeBaseCategoryUpdateService categoryUpdateService;
    /** Handles the document-name search endpoint. */
    private final KnowledgeBaseSearchService searchService;
    /** Handles the aggregate statistics endpoint. */
    private final KnowledgeBaseStatsService statsService;
    /** Handles the vector rebuild endpoint. */
    private final KnowledgeBaseRevectorizeService revectorizeService;

    /** Injects the ten endpoint-specific application services. */
    public KnowledgeBaseController(KnowledgeBaseUploadService uploadService, KnowledgeBaseListService listService,
                                   KnowledgeBaseDownloadService downloadService, KnowledgeBaseDeleteService deleteService,
                                   KnowledgeBaseCategoryListService categoryListService,
                                   KnowledgeBaseCategoryQueryService categoryQueryService,
                                   KnowledgeBaseCategoryUpdateService categoryUpdateService,
                                   KnowledgeBaseSearchService searchService, KnowledgeBaseStatsService statsService,
                                   KnowledgeBaseRevectorizeService revectorizeService) {
        this.uploadService = uploadService;
        this.listService = listService;
        this.downloadService = downloadService;
        this.deleteService = deleteService;
        this.categoryListService = categoryListService;
        this.categoryQueryService = categoryQueryService;
        this.categoryUpdateService = categoryUpdateService;
        this.searchService = searchService;
        this.statsService = statsService;
        this.revectorizeService = revectorizeService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    /** Uploads one source document and schedules its vector indexing. */
    public ApiResult<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String name,
            @RequestPart(value = "category", required = false) String category,
            @RequestPart(value = "sourceUrl", required = false) String sourceUrl,
            @RequestPart(value = "sourceTitle", required = false) String sourceTitle,
            @RequestPart(value = "sourceFetchedAt", required = false) String sourceFetchedAt,
            @RequestPart(value = "sourceHash", required = false) String sourceHash,
            @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
        return ApiResult.success(uploadService.upload(
                file, name, category, userId, sourceUrl, sourceTitle, sourceFetchedAt, sourceHash));
    }

    @GetMapping("/list")
    /** Returns caller-owned documents with the optional supported filters. */
    public ApiResult<List<KnowledgeBaseResponse>> list(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(listService.list(userId, sortBy, vectorStatus));
    }

    @GetMapping("/{id}/download")
    /** Returns the original document bytes for a caller-owned record. */
    public ResponseEntity<byte[]> download(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return downloadService.download(id, userId);
    }

    @DeleteMapping("/{id}")
    /** Removes the record after lower-layer vector cleanup succeeds. */
    public ApiResult<Void> delete(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        deleteService.delete(id, userId);
        return ApiResult.success(null);
    }

    @GetMapping("/categories")
    /** Lists distinct non-empty categories visible to the caller. */
    public ApiResult<List<String>> categories(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(categoryListService.categories(userId));
    }

    @GetMapping("/category/{category}")
    /** Lists caller-owned documents belonging to one category. */
    public ApiResult<List<KnowledgeBaseResponse>> byCategory(@PathVariable String category,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(categoryQueryService.query(category, userId));
    }

    @PutMapping("/{id}/category")
    /** Updates the category of one caller-owned document. */
    public ApiResult<Void> updateCategory(@PathVariable long id, @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        categoryUpdateService.update(id, body.get("category"), userId);
        return ApiResult.success(null);
    }

    @GetMapping("/search")
    /** Searches the caller's document names. */
    public ApiResult<List<KnowledgeBaseResponse>> search(@RequestParam String keyword,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(searchService.search(keyword, userId));
    }

    @GetMapping("/stats")
    /** Returns list-consistent totals grouped by vector state. */
    public ApiResult<Map<String, Object>> stats(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(statsService.stats(userId));
    }

    @PostMapping("/{id}/revectorize")
    /** Resets one document to pending and queues a fresh vector-index task. */
    public ApiResult<Void> revectorize(@PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        revectorizeService.revectorize(id, userId);
        return ApiResult.success(null);
    }
}
