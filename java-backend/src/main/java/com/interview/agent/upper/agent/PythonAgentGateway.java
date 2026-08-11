package com.interview.agent.upper.agent;

import com.interview.agent.upper.agent.dto.AgentInitializeRequest;
import com.interview.agent.upper.agent.dto.AgentRespondRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentRagDeleteRequest;
import com.interview.agent.upper.agent.dto.AgentCompleteRequest;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentResumeMemoryActivationRequest;
import com.interview.agent.upper.agent.dto.AgentSkillRequest;
import com.interview.agent.upper.agent.dto.AgentWebFetchRequest;
import com.interview.agent.upper.engineering.reliability.AgentCallExecutor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PythonAgentGateway implements AgentGateway {
    private final RestClient restClient;
    private final AgentCallExecutor callExecutor;
    private final Validator validator;

    public PythonAgentGateway(
            RestClient agentRestClient, AgentCallExecutor callExecutor, Validator validator) {
        this.restClient = agentRestClient;
        this.callExecutor = callExecutor;
        this.validator = validator;
    }

    @Override
    public AgentResponse initialize(AgentInitializeRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/sessions/initialize", request));
    }

    @Override
    public AgentResponse respond(AgentRespondRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/respond", request));
    }

    @Override
    public AgentResponse complete(AgentCompleteRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/sessions/complete", request));
    }

    @Override
    public AgentResponse evaluateResume(AgentResumeEvaluateRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/evaluate/resume", request));
    }

    @Override
    public AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/resume/activate", request));
    }

    @Override
    public AgentResponse indexRag(AgentRagIndexRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/rag/index", request));
    }

    @Override
    public AgentResponse deleteRag(AgentRagDeleteRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/rag/delete", request));
    }

    @Override
    public AgentResponse skills(AgentSkillRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/skills", request));
    }

    @Override
    public AgentResponse fetchWeb(AgentWebFetchRequest request) {
        return callExecutor.execute(() -> post("/v1/tools/web/fetch", request));
    }

    private AgentResponse post(String path, Object request) {
        validateRequest(request);
        try {
            AgentResponse response = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(AgentResponse.class);
            if (response == null) {
                throw new AgentGatewayException("下层返回空响应", null, true);
            }
            return response;
        } catch (AgentGatewayException error) {
            throw error;
        } catch (RestClientResponseException error) {
            // Python 对参数、权限和一致性错误使用 HTTP 4xx + 统一 JSON；这些错误
            // 重试不会改变输入，只有 HTTP 5xx 才按下层临时故障处理。
            boolean retryable = error.getStatusCode().is5xxServerError();
            throw new AgentGatewayException("下层 Agent HTTP 调用失败", error, retryable);
        } catch (RestClientException error) {
            // 无 HTTP 响应（连接失败、超时等）属于可恢复的网络故障。
            throw new AgentGatewayException("下层 Agent 网络调用失败", error, true);
        }
    }

    private void validateRequest(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        String invalidFields = violations.stream()
                .map(item -> item.getPropertyPath().toString())
                .sorted()
                .collect(Collectors.joining(", "));
        throw new AgentGatewayException(
                "lower Agent request has invalid fields: " + invalidFields, null, false);
    }
}
