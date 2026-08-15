package com.interviewguide.pythonagent.mapper;

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

import java.util.Map;

/**
 * The only port through which Java business modules may call the Python service.
 *
 * <p>This is deliberately an integration contract, not an Agent implementation.
 * Planner, evaluation, RAG, memory and tools remain in {@code python-agent/}.</p>
 */
public interface PythonAgentClient {
    AgentResponse initialize(AgentInitializeRequest request);
    AgentResponse respond(AgentRespondRequest request);
    AgentResponse complete(AgentCompleteRequest request);
    AgentResponse evaluateResume(AgentResumeEvaluateRequest request);
    AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request);
    AgentResponse indexRag(AgentRagIndexRequest request);
    AgentResponse deleteRag(AgentRagDeleteRequest request);
    AgentResponse fetchWeb(AgentWebFetchRequest request);
    AgentResponse crawlWeb(AgentWebCrawlRequest request);
    Map<String, Object> sessionProgress(String sessionId);
}
