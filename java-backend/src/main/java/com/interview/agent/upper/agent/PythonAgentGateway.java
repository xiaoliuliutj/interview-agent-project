package com.interview.agent.upper.agent;

import com.interview.agent.upper.agent.dto.AgentInitializeRequest;
import com.interview.agent.upper.agent.dto.AgentRespondRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentRagRequest;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentCompleteRequest;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentSkillRequest;
import com.interview.agent.upper.agent.dto.AgentScheduleParseRequest;
import com.interview.agent.upper.engineering.reliability.AgentCallExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PythonAgentGateway implements AgentGateway {
    private final RestClient restClient;
    private final AgentCallExecutor callExecutor;

    public PythonAgentGateway(RestClient agentRestClient, AgentCallExecutor callExecutor) {
        this.restClient = agentRestClient;
        this.callExecutor = callExecutor;
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
    public AgentResponse searchRag(AgentRagRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/rag/search", request));
    }

    @Override
    public AgentResponse indexRag(AgentRagIndexRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/rag/index", request));
    }

    @Override
    public AgentResponse skills(AgentSkillRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/skills", request));
    }

    @Override
    public AgentResponse parseSchedule(AgentScheduleParseRequest request) {
        return callExecutor.execute(() -> post("/v1/agent/schedule/parse", request));
    }

    private AgentResponse post(String path, Object request) {
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
        } catch (RestClientException error) {
            throw new AgentGatewayException("下层 Agent 网络调用失败", error, true);
        }
    }
}
