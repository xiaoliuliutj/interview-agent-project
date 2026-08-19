package com.interviewguide.pythonagent.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewguide.pythonagent.domain.AgentCompleteRequest;
import com.interviewguide.pythonagent.domain.AgentInitializeRequest;
import com.interviewguide.pythonagent.domain.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.domain.AgentRagIndexRequest;
import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.pythonagent.domain.AgentResumeEvaluateRequest;
import com.interviewguide.pythonagent.domain.AgentResumeMemoryActivationRequest;
import com.interviewguide.pythonagent.domain.AgentRespondRequest;
import com.interviewguide.pythonagent.domain.AgentWebCrawlRequest;
import com.interviewguide.pythonagent.domain.AgentWebFetchRequest;
import com.interviewguide.common.exception.PythonAgentException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** HTTP adapter only. It contains no Agent, Skill, RAG or memory implementation. */
@Component
@Primary
public class HttpPythonAgentMapper implements PythonAgentMapper {
    private final RestClient restClient;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public HttpPythonAgentMapper(RestClient agentRestClient, Validator validator, ObjectMapper objectMapper) {
        this.restClient = agentRestClient;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentResponse initialize(AgentInitializeRequest request) {
        // Post the interview initialization contract to Python.
        return post("/v1/agent/sessions/initialize", request);
    }

    @Override
    public AgentResponse respond(AgentRespondRequest request) {
        // Post one interview answer and retrieve the next state.
        return post("/v1/agent/respond", request);
    }

    @Override
    public AgentResponse complete(AgentCompleteRequest request) {
        // Post the session-completion request to Python.
        return post("/v1/agent/sessions/complete", request);
    }

    @Override
    public AgentResponse evaluateResume(AgentResumeEvaluateRequest request) {
        // Post the stored resume evaluation request to Python.
        return post("/v1/agent/evaluate/resume", request);
    }

    @Override
    public AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request) {
        // Post the resume-memory activation request to Python.
        return post("/v1/agent/resume/activate", request);
    }

    @Override
    public AgentResponse indexRag(AgentRagIndexRequest request) {
        // Post the knowledge-base vector-index request to Python.
        return post("/v1/agent/rag/index", request);
    }

    @Override
    public AgentResponse deleteRag(AgentRagDeleteRequest request) {
        // Post the knowledge-base vector-delete request to Python.
        return post("/v1/agent/rag/delete", request);
    }

    @Override
    public AgentResponse fetchWeb(AgentWebFetchRequest request) {
        // Post the bounded public-web fetch request to Python.
        return post("/v1/tools/web/fetch", request);
    }

    @Override
    public AgentResponse crawlWeb(AgentWebCrawlRequest request) {
        // Post the bounded public-web crawl request to Python.
        return post("/v1/tools/web/crawl", request);
    }

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
