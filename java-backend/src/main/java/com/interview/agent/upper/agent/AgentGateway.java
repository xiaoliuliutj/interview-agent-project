package com.interview.agent.upper.agent;

import com.interview.agent.upper.agent.dto.AgentInitializeRequest;
import com.interview.agent.upper.agent.dto.AgentRespondRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentRagRequest;
import com.interview.agent.upper.agent.dto.AgentRagIndexRequest;
import com.interview.agent.upper.agent.dto.AgentCompleteRequest;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentSkillRequest;

public interface AgentGateway {
    AgentResponse initialize(AgentInitializeRequest request);

    AgentResponse respond(AgentRespondRequest request);

    AgentResponse complete(AgentCompleteRequest request);

    AgentResponse evaluateResume(AgentResumeEvaluateRequest request);

    AgentResponse searchRag(AgentRagRequest request);

    AgentResponse indexRag(AgentRagIndexRequest request);

    AgentResponse skills(AgentSkillRequest request);
}
