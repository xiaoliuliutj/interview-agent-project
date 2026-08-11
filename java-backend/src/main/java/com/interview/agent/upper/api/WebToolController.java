package com.interview.agent.upper.api;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentWebFetchRequest;
import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.UserIdentityResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Candidate-facing facade for the bounded public-web Agent tool. */
@RestController
@RequestMapping("/api/tools/web")
public class WebToolController {
    private final AgentGateway agentGateway;
    private final UserIdentityResolver identity;

    public WebToolController(AgentGateway agentGateway, UserIdentityResolver identity) {
        this.agentGateway = agentGateway;
        this.identity = identity;
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

    public record FetchRequest(@NotBlank @Size(max = 2048) String url) {
    }
}
