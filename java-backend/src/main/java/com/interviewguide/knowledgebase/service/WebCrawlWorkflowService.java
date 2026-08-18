package com.interviewguide.knowledgebase.service;

import com.interviewguide.pythonagent.mapper.PythonAgentMapper;
import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.pythonagent.domain.AgentWebCrawlRequest;
import com.interviewguide.pythonagent.domain.AgentWebFetchRequest;
import com.interviewguide.common.security.UserIdentityResolver;
import com.interviewguide.common.python.PythonAgentWebResponseValidator;
import com.interviewguide.knowledgebase.service.WebCrawlPreviewService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Application service for public-web tools; controllers never call Python directly. */
@Service
public class WebCrawlWorkflowService {
    private final PythonAgentMapper pythonAgentClient;
    private final UserIdentityResolver identity;
    private final WebCrawlPreviewService previews;

    public WebCrawlWorkflowService(PythonAgentMapper pythonAgentClient, UserIdentityResolver identity,
                          WebCrawlPreviewService previews) {
        this.pythonAgentClient = pythonAgentClient;
        this.identity = identity;
        this.previews = previews;
    }

    public Map<String, Object> fetch(String userId, String url) {
        AgentResponse response = pythonAgentClient.fetchWeb(new AgentWebFetchRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-tool", "tool.web.fetch", url, Instant.now()));
        return PythonAgentWebResponseValidator.requireOutput(response, "WEB_FETCH_FAILED", "web page fetch failed");
    }

    public Map<String, Object> crawl(String userId, String url, String topic) {
        AgentResponse response = pythonAgentClient.crawlWeb(new AgentWebCrawlRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                identity.require(userId), "web-crawl", "tool.web.crawl", url, topic, Instant.now()));
        return previews.save(userId,
                PythonAgentWebResponseValidator.requireOutput(response, "WEB_CRAWL_FAILED", "web crawl failed"));
    }

    public List<WebCrawlPreviewService.ImportedPage> importCrawl(
            String userId, String previewToken, List<String> pageIds, String category) {
        return previews.importSelected(userId, previewToken, pageIds, category);
    }

    public com.interviewguide.knowledgebase.domain.WebArchiveEntity archive(String userId, String token) {
        return previews.archive(userId, token);
    }

}
