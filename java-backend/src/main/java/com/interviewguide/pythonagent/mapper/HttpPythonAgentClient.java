package com.interviewguide.pythonagent.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.infrastructure.reliability.AgentCallExecutor;
import com.interviewguide.pythonagent.dto.AgentCompleteRequest;
import com.interviewguide.pythonagent.dto.AgentInitializeRequest;
import com.interviewguide.pythonagent.dto.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.dto.AgentRagIndexRequest;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.pythonagent.dto.AgentResumeEvaluateRequest;
import com.interviewguide.pythonagent.dto.AgentResumeMemoryActivationRequest;
import com.interviewguide.pythonagent.dto.AgentRespondRequest;
import com.interviewguide.pythonagent.dto.AgentWebCrawlRequest;
import com.interviewguide.pythonagent.dto.AgentWebFetchRequest;
import com.interviewguide.pythonagent.exception.PythonAgentException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** HTTP adapter only. It contains no Agent, Skill, RAG or memory implementation. */
@Component
public class HttpPythonAgentClient implements PythonAgentClient {
    private final RestClient restClient;
    private final AgentCallExecutor callExecutor;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public HttpPythonAgentClient(RestClient agentRestClient, AgentCallExecutor callExecutor,
                                 Validator validator, ObjectMapper objectMapper) {
        this.restClient = agentRestClient;
        this.callExecutor = callExecutor;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override public AgentResponse initialize(AgentInitializeRequest request) { return callExecutor.execute(() -> post("/v1/agent/sessions/initialize", request)); }
    @Override public AgentResponse respond(AgentRespondRequest request) { return callExecutor.execute(() -> post("/v1/agent/respond", request)); }
    @Override public AgentResponse complete(AgentCompleteRequest request) { return callExecutor.execute(() -> post("/v1/agent/sessions/complete", request)); }
    @Override public AgentResponse evaluateResume(AgentResumeEvaluateRequest request) { return callExecutor.execute(() -> post("/v1/agent/evaluate/resume", request)); }
    @Override public AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request) { return callExecutor.execute(() -> post("/v1/agent/resume/activate", request)); }
    @Override public AgentResponse indexRag(AgentRagIndexRequest request) { return callExecutor.execute(() -> post("/v1/agent/rag/index", request)); }
    @Override public AgentResponse deleteRag(AgentRagDeleteRequest request) { return callExecutor.execute(() -> post("/v1/agent/rag/delete", request)); }
    @Override public AgentResponse fetchWeb(AgentWebFetchRequest request) { return callExecutor.execute(() -> post("/v1/tools/web/fetch", request)); }
    @Override public AgentResponse crawlWeb(AgentWebCrawlRequest request) { return callExecutor.execute(() -> post("/v1/tools/web/crawl", request)); }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> sessionProgress(String sessionId) {
        try {
            Map<String, Object> response = restClient.get().uri("/v1/agent/sessions/{sessionId}/progress", sessionId)
                    .retrieve().body(Map.class);
            return response == null ? Map.of("stage", "IDLE") : response;
        } catch (RestClientException error) {
            return Map.of("stage", "STATUS_UNAVAILABLE");
        }
    }

    private AgentResponse post(String path, Object request) {
        validateRequest(request);
        try {
            AgentResponse response = restClient.post().uri(path).body(request).retrieve().body(AgentResponse.class);
            if (response == null) throw new PythonAgentException("Python service returned an empty response", null, true);
            return response;
        } catch (PythonAgentException error) {
            throw error;
        } catch (RestClientResponseException error) {
            AgentResponse structuredError = parseStructuredError(error);
            if (structuredError != null) return structuredError;
            throw new PythonAgentException("Python service HTTP call failed", error, error.getStatusCode().is5xxServerError());
        } catch (RestClientException error) {
            throw new PythonAgentException("Python service network call failed", error, true);
        }
    }

    private AgentResponse parseStructuredError(RestClientResponseException error) {
        try {
            AgentResponse response = objectMapper.readValue(error.getResponseBodyAsString(), AgentResponse.class);
            return response.error() == null ? null : response;
        } catch (Exception ignored) { return null; }
    }

    private void validateRequest(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String fields = violations.stream().map(item -> item.getPropertyPath().toString()).sorted()
                    .collect(Collectors.joining(", "));
            throw new PythonAgentException("Python service request has invalid fields: " + fields, null, false);
        }
    }
}
