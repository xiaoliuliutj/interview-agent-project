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
import com.interview.agent.upper.agent.dto.AgentWebCrawlRequest;
import java.util.Map;

public interface AgentGateway {
    AgentResponse initialize(AgentInitializeRequest request);

    AgentResponse respond(AgentRespondRequest request);

    AgentResponse complete(AgentCompleteRequest request);

    AgentResponse evaluateResume(AgentResumeEvaluateRequest request);

    AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request);

    AgentResponse indexRag(AgentRagIndexRequest request);

    AgentResponse deleteRag(AgentRagDeleteRequest request);

    AgentResponse skills(AgentSkillRequest request);

    AgentResponse fetchWeb(AgentWebFetchRequest request);
    AgentResponse crawlWeb(AgentWebCrawlRequest request);

    Map<String, Object> sessionProgress(String sessionId);

}
