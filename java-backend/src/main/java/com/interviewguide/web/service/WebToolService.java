package com.interviewguide.web.service;

import com.interviewguide.knowledgebase.service.KnowledgeBaseService;

import com.interviewguide.pythonagent.mapper.PythonAgentClient;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.pythonagent.dto.AgentWebCrawlRequest;
import com.interviewguide.pythonagent.dto.AgentWebFetchRequest;
import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.common.security.UserIdentityResolver;
import com.interviewguide.web.service.WebCrawlPreviewService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Application service for public-web tools; controllers never call Python directly. */
@Service
public class WebToolService {
    private final PythonAgentClient pythonAgentClient;
    private final UserIdentityResolver identity;
    private final WebCrawlPreviewService previews;

    public WebToolService(PythonAgentClient pythonAgentClient, UserIdentityResolver identity,
                          WebCrawlPreviewService previews) {
        this.pythonAgentClient = pythonAgentClient;
        this.identity = identity;
        this.previews = previews;
    }

    public Map<String, Object> fetch(String userId, String url) {
        AgentResponse response = pythonAgentClient.fetchWeb(new AgentWebFetchRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-tool", "tool.web.fetch", url, Instant.now()));
        return output(response, "WEB_FETCH_FAILED", "web page fetch failed");
    }

    public Map<String, Object> crawl(String userId, String url, String topic) {
        AgentResponse response = pythonAgentClient.crawlWeb(new AgentWebCrawlRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-crawl", "tool.web.crawl", url, topic, Instant.now()));
        return previews.save(userId, output(response, "WEB_CRAWL_FAILED", "web crawl failed"));
    }

    public List<WebCrawlPreviewService.ImportedPage> importCrawl(
            String userId, String previewToken, List<String> pageIds, String category) {
        return previews.importSelected(userId, previewToken, pageIds, category);
    }

    public com.interviewguide.knowledgebase.service.KnowledgeBaseService.DownloadedDocument archive(String userId, String token) {
        return previews.archive(userId, token);
    }

    private Map<String, Object> output(AgentResponse response, String code, String fallback) {
        if (response == null || response.code() < 100 || response.code() >= 200 || response.output() == null) {
            String message = response != null && response.error() != null ? response.error().message() : fallback;
            throw new BusinessException(code, message);
        }
        return response.output();
    }
}
