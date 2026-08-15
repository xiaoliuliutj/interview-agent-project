package com.interviewguide.web.controller;

import com.interviewguide.web.service.WebToolService;
import com.interviewguide.common.web.dto.ApiResult;
import com.interviewguide.knowledgebase.service.KnowledgeBaseService;
import com.interviewguide.web.service.WebCrawlPreviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;

/** Candidate-facing facade for the bounded public-web Agent tool. */
@RestController
@RequestMapping("/api/tools/web")
public class WebToolController {
    private final WebToolService webToolService;

    public WebToolController(WebToolService webToolService) {
        this.webToolService = webToolService;
    }

    @PostMapping("/fetch")
    public ApiResult<Map<String, Object>> fetch(
            @Valid @RequestBody FetchRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(webToolService.fetch(userId, body.url()));
    }

    @PostMapping("/crawl/import")
    public ApiResult<Map<String, Object>> importCrawl(
            @Valid @RequestBody CrawlImportRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        List<WebCrawlPreviewService.ImportedPage> imported = webToolService.importCrawl(
                userId, body.previewToken(), body.selectedPageIds(), body.category());
        return ApiResult.success(Map.of("importedCount", imported.size(), "knowledgeBases", imported));
    }

    @GetMapping("/crawl/{previewToken}/archive")
    public ResponseEntity<byte[]> downloadArchive(
            @PathVariable String previewToken,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        KnowledgeBaseService.DownloadedDocument document = webToolService.archive(userId, previewToken);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.filename() + "\"")
                .body(document.content());
    }

    @PostMapping("/crawl")
    public ApiResult<Map<String, Object>> crawl(
            @Valid @RequestBody CrawlRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(webToolService.crawl(userId, body.url(), body.topic()));
    }

    public record FetchRequest(@NotBlank @Size(max = 2048) String url) {
    }

    public record CrawlRequest(@NotBlank @Size(max = 2048) String url, @Size(max = 500) String topic) {
    }

    public record CrawlImportRequest(@NotBlank String previewToken,
            @NotNull @Size(min = 1, max = 20) List<@NotBlank String> selectedPageIds,
            @Size(max = 100) String category) {
    }
}
