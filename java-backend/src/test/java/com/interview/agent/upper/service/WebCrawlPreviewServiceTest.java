package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WebCrawlPreviewServiceTest {
    @Test
    void previewIsOwnerScopedAndImportsOnlySelectedServerSidePages() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        UserIdentityResolver identity = new UserIdentityResolver();
        WebCrawlPreviewService service = new WebCrawlPreviewService(knowledgeBases, identity);
        when(knowledgeBases.uploadMarkdown(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(view(101));

        Map<String, Object> saved = service.save("user-a", crawlOutput());
        String token = (String) saved.get("previewToken");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) saved.get("pages");
        String pageId = (String) pages.getFirst().get("id");

        assertThrows(BusinessException.class, () -> service.archive("user-b", token));
        List<WebCrawlPreviewService.ImportedPage> imported = service.importSelected(
                "user-a", token, List.of(pageId), null);

        assertEquals(1, imported.size());
        verify(knowledgeBases).uploadMarkdown(eq("000-page.md"), eq("Page"), eq(null),
                eq("user-a"), any(), eq("https://example.com/page"), eq("Page"), any(), eq("hash"));
        assertTrue(service.archive("user-a", token).content().length > 0);
        service.importSelected("user-a", token, List.of(pageId), null);
        verify(knowledgeBases, times(1)).uploadMarkdown(eq("000-page.md"), eq("Page"), eq(null),
                eq("user-a"), any(), eq("https://example.com/page"), eq("Page"), any(), eq("hash"));
    }

    @Test
    void archiveMustBeExplicitlyExcludedFromRag() {
        WebCrawlPreviewService service = new WebCrawlPreviewService(
                mock(KnowledgeBaseService.class), new UserIdentityResolver());
        Map<String, Object> output = crawlOutput();
        output.put("archiveMarkdown", "# archive without flag");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.save("user-a", output));
        assertEquals("WEB_CRAWL_RESULT_INVALID", error.code());
    }

    private static Map<String, Object> crawlOutput() {
        String pageMarkdown = "---\nrag_index_enabled: true\n---\n# Page\n\n" + "technical content ".repeat(30);
        return new java.util.LinkedHashMap<>(Map.of(
                "entryUrl", "https://example.com",
                "status", "COMPLETED",
                "validPageCount", 1,
                "rejectedCount", 0,
                "rejected", List.of(),
                "archiveMarkdown", "---\nrag_index_enabled: false\n---\n# sources\n",
                "pages", List.of(Map.of(
                        "url", "https://example.com/page", "title", "Page",
                        "fetchedAt", "2026-08-12T00:00:00Z", "contentHash", "hash",
                        "markdown", pageMarkdown, "depth", 1, "filename", "000-page.md"))));
    }

    private static KnowledgeBaseView view(long id) {
        return new KnowledgeBaseView(id, "Page", null, "000-page.md", 100,
                "text/markdown", Instant.now(), Instant.now(), "PENDING", null, 0,
                "https://example.com/page", "Page", Instant.now(), "hash");
    }
}
