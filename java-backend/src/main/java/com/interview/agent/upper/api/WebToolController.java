package com.interview.agent.upper.api;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentWebFetchRequest;
import com.interview.agent.upper.agent.dto.AgentWebCrawlRequest;
import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.UserIdentityResolver;
import com.interview.agent.upper.service.KnowledgeBaseService;
import com.interview.agent.upper.service.WebCrawlPreviewService;
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

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Candidate-facing facade for the bounded public-web Agent tool. */
@RestController
@RequestMapping("/api/tools/web")
public class WebToolController {
    private final AgentGateway agentGateway;
    private final UserIdentityResolver identity;
    private final WebCrawlPreviewService crawlPreviews;

    public WebToolController(AgentGateway agentGateway, UserIdentityResolver identity,
            WebCrawlPreviewService crawlPreviews) {
        this.agentGateway = agentGateway;
        this.identity = identity;
        this.crawlPreviews = crawlPreviews;
    }

    @PostMapping("/fetch")
    public ApiResult<Map<String, Object>> fetch(
            @Valid @RequestBody FetchRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        AgentResponse response = agentGateway.fetchWeb(new AgentWebFetchRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-tool", "tool.web.fetch", body.url(), Instant.now()));
        if (response == null || response.code() < 100 || response.code() >= 200 || response.output() == null) {
            String message = response != null && response.error() != null
                    ? response.error().message() : "web page fetch failed";
            throw new BusinessException("WEB_FETCH_FAILED", message);
        }
        return ApiResult.success(response.output());
    }

    @PostMapping("/crawl/import")
    public ApiResult<Map<String, Object>> importCrawl(
            @Valid @RequestBody CrawlImportRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        List<WebCrawlPreviewService.ImportedPage> imported = crawlPreviews.importSelected(
                userId, body.previewToken(), body.selectedPageIds(), body.category());
        return ApiResult.success(Map.of("importedCount", imported.size(), "knowledgeBases", imported));
    }

    @GetMapping("/crawl/{previewToken}/archive")
    public ResponseEntity<byte[]> downloadArchive(
            @PathVariable String previewToken,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        KnowledgeBaseService.DownloadedDocument document = crawlPreviews.archive(userId, previewToken);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.filename() + "\"")
                .body(document.content());
    }

    @PostMapping("/crawl")
    public ApiResult<Map<String, Object>> crawl(
            @Valid @RequestBody CrawlRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        AgentResponse response = agentGateway.crawlWeb(new AgentWebCrawlRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-crawl", "tool.web.crawl", body.url(), body.topic(), Instant.now()));
        if (response == null || response.code() < 100 || response.code() >= 200 || response.output() == null) {
            String message = response != null && response.error() != null
                    ? response.error().message() : "web crawl failed";
            throw new BusinessException("WEB_CRAWL_FAILED", message);
        }
        return ApiResult.success(crawlPreviews.save(userId, response.output()));
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
