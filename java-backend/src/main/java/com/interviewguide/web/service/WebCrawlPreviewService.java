package com.interviewguide.web.service;

import com.interviewguide.knowledgebase.service.KnowledgeBaseService;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.common.security.UserIdentityResolver;

import com.interviewguide.knowledgebase.dto.KnowledgeBaseView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived, owner-scoped storage for crawl previews and provenance archives. */
@Service
public class WebCrawlPreviewService {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_PREVIEWS = 20;
    private static final int MAX_PREVIEWS_PER_OWNER = 3;
    private static final int MAX_VALID_PAGES = 20;
    private static final int MAX_PAGE_MARKDOWN_CHARS = 180_000;
    private static final int MAX_TOTAL_MARKDOWN_CHARS = 1_500_000;

    public record Page(String id, String url, String title, String fetchedAt, String contentHash,
            String markdown, int characterCount, int depth, String parentUrl, String filename) {}

    public record Preview(String token, String ownerId, Instant createdAt, Instant expiresAt,
            String entryUrl, String status, String stopReason, List<Page> pages,
            List<Map<String, String>> rejected, String archiveMarkdown,
            ConcurrentHashMap<String, ImportedPage> importedPages) {}

    public record ImportedPage(long id, String name, String filename, String vectorStatus) {}

    private final ConcurrentHashMap<String, Preview> previews = new ConcurrentHashMap<>();
    private final KnowledgeBaseService knowledgeBaseService;
    private final UserIdentityResolver identity;

    public WebCrawlPreviewService(KnowledgeBaseService knowledgeBaseService, UserIdentityResolver identity) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.identity = identity;
    }

    public Map<String, Object> save(String userId, Map<String, Object> output) {
        String ownerId = identity.require(userId);
        removeExpired();
        long ownerCount = previews.values().stream().filter(item -> item.ownerId().equals(ownerId)).count();
        if (previews.size() >= MAX_PREVIEWS || ownerCount >= MAX_PREVIEWS_PER_OWNER) {
            throw new BusinessException("WEB_CRAWL_PREVIEW_LIMIT", "too many active crawl previews; import or wait for expiry");
        }
        List<Page> pages = parsePages(output.get("pages"));
        String archive = stringValue(output.get("archiveMarkdown"));
        if (pages.size() > MAX_VALID_PAGES
                || pages.stream().anyMatch(page -> page.url().isBlank()
                        || page.title().isBlank() || page.markdown().isBlank()
                        || page.markdown().length() > MAX_PAGE_MARKDOWN_CHARS
                        || !page.markdown().contains("rag_index_enabled: true"))
                || pages.stream().mapToInt(page -> page.markdown().length()).sum() > MAX_TOTAL_MARKDOWN_CHARS
                || archive.isBlank() || !archive.contains("rag_index_enabled: false")
                || archive.length() > MAX_TOTAL_MARKDOWN_CHARS * 2) {
            throw new BusinessException("WEB_CRAWL_RESULT_INVALID", "crawl result exceeds server preview limits");
        }
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        Preview preview = new Preview(token, ownerId, now, now.plus(TTL),
                stringValue(output.get("entryUrl")), stringValue(output.get("status")),
                nullableString(output.get("stopReason")), pages, parseRejected(output.get("rejected")), archive,
                new ConcurrentHashMap<>());
        previews.put(token, preview);

        Map<String, Object> result = new LinkedHashMap<>(output);
        result.put("previewToken", token);
        result.put("expiresAt", preview.expiresAt().toString());
        result.put("pages", pages.stream().map(this::pageMap).toList());
        return result;
    }

    public List<ImportedPage> importSelected(String userId, String token, List<String> selectedPageIds,
            String category) {
        Preview preview = requireOwned(userId, token);
        if (selectedPageIds == null || selectedPageIds.isEmpty()) {
            throw new BusinessException("WEB_CRAWL_SELECTION_REQUIRED", "select at least one page to import");
        }
        Set<String> selected = Set.copyOf(selectedPageIds);
        if (selected.size() != selectedPageIds.size()) {
            throw new BusinessException("WEB_CRAWL_SELECTION_INVALID", "selected page IDs must be unique");
        }
        List<Page> pages = preview.pages().stream().filter(page -> selected.contains(page.id())).toList();
        if (pages.size() != selected.size()) {
            throw new BusinessException("WEB_CRAWL_SELECTION_INVALID", "selection contains an unknown page");
        }
        List<ImportedPage> imported = new ArrayList<>();
        synchronized (preview) {
            for (Page page : pages) {
                ImportedPage existing = preview.importedPages().get(page.id());
                if (existing != null) {
                    imported.add(existing);
                    continue;
                }
                KnowledgeBaseView view = knowledgeBaseService.uploadMarkdown(
                        page.filename(), page.title(), category, preview.ownerId(), page.markdown(),
                        page.url(), page.title(), parseInstant(page.fetchedAt()), page.contentHash());
                ImportedPage created = new ImportedPage(
                        view.id(), view.name(), view.originalFilename(), view.vectorStatus());
                preview.importedPages().put(page.id(), created);
                imported.add(created);
            }
        }
        return imported;
    }

    public KnowledgeBaseService.DownloadedDocument archive(String userId, String token) {
        Preview preview = requireOwned(userId, token);
        return new KnowledgeBaseService.DownloadedDocument(
                "web-crawl-sources.md", "text/markdown;charset=UTF-8",
                preview.archiveMarkdown().getBytes(StandardCharsets.UTF_8));
    }

    private Preview requireOwned(String userId, String token) {
        String ownerId = identity.require(userId);
        if (token == null || token.isBlank()) {
            throw new BusinessException("WEB_CRAWL_PREVIEW_REQUIRED", "crawl preview token is required");
        }
        Preview preview = previews.get(token);
        if (preview == null || preview.expiresAt().isBefore(Instant.now())) {
            if (preview != null) previews.remove(token, preview);
            throw new BusinessException("WEB_CRAWL_PREVIEW_EXPIRED", "crawl preview was not found or has expired");
        }
        if (!preview.ownerId().equals(ownerId)) {
            throw new BusinessException("WEB_CRAWL_PREVIEW_ACCESS_DENIED", "crawl preview does not belong to this user");
        }
        return preview;
    }

    private void removeExpired() {
        Instant now = Instant.now();
        previews.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private List<Page> parsePages(Object value) {
        if (!(value instanceof List<?> rawPages)) return List.of();
        List<Page> pages = new ArrayList<>();
        for (int index = 0; index < rawPages.size(); index++) {
            if (!(rawPages.get(index) instanceof Map<?, ?> raw)) continue;
            String markdown = stringValue(raw.get("markdown"));
            pages.add(new Page("page-" + index, stringValue(raw.get("url")),
                    stringValue(raw.get("title")), stringValue(raw.get("fetchedAt")),
                    stringValue(raw.get("contentHash")), markdown, markdown.length(),
                    intValue(raw.get("depth")), nullableString(raw.get("parentUrl")),
                    safeFilename(stringValue(raw.get("filename")), index)));
        }
        return List.copyOf(pages);
    }

    private List<Map<String, String>> parseRejected(Object value) {
        if (!(value instanceof List<?> rawItems)) return List.of();
        List<Map<String, String>> items = new ArrayList<>();
        for (Object item : rawItems) {
            if (item instanceof Map<?, ?> raw) {
                items.add(Map.of("url", stringValue(raw.get("url")), "reason", stringValue(raw.get("reason"))));
            }
        }
        return List.copyOf(items);
    }

    private Map<String, Object> pageMap(Page page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", page.id()); result.put("url", page.url()); result.put("title", page.title());
        result.put("fetchedAt", page.fetchedAt()); result.put("contentHash", page.contentHash());
        result.put("markdown", page.markdown()); result.put("characterCount", page.characterCount());
        result.put("depth", page.depth()); result.put("parentUrl", page.parentUrl());
        result.put("filename", page.filename());
        return result;
    }

    private static String safeFilename(String filename, int index) {
        String value = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        return value.isBlank() ? String.format("%03d-web-page.md", index) : value.substring(0, Math.min(value.length(), 180));
    }

    private static Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
