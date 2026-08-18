package com.interviewguide.pythonagent.mapper;

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

import java.util.Map;

/**
 * The only port through which Java business modules may call the Python service.
 *
 * <p>This is deliberately an integration contract, not an Agent implementation.
 * Planner, evaluation, RAG, memory and tools remain in {@code python-agent/}.</p>
 */
public interface PythonAgentMapper {
    /** Initializes Python state for a newly persisted interview session. */
    AgentResponse initialize(AgentInitializeRequest request);
    /** Submits one interview answer and receives the next agent state. */
    AgentResponse respond(AgentRespondRequest request);
    /** Requests final evaluation and completion of one interview session. */
    AgentResponse complete(AgentCompleteRequest request);
    /** Requests AI evaluation for one stored resume. */
    AgentResponse evaluateResume(AgentResumeEvaluateRequest request);
    /** Activates a resume in the Python memory context before evaluation. */
    AgentResponse activateResumeMemory(AgentResumeMemoryActivationRequest request);
    /** Builds RAG vectors for one persisted knowledge-base document. */
    AgentResponse indexRag(AgentRagIndexRequest request);
    /** Removes RAG vectors for one deleted knowledge-base document. */
    AgentResponse deleteRag(AgentRagDeleteRequest request);
    /** Fetches one public web page through the bounded Python tool. */
    AgentResponse fetchWeb(AgentWebFetchRequest request);
    /** Crawls a bounded public website and returns its preview payload. */
    AgentResponse crawlWeb(AgentWebCrawlRequest request);
    /** Reads Python-side progress for one interview session without changing state. */
    Map<String, Object> sessionProgress(String sessionId);
}
