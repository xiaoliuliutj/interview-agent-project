package com.interviewguide.interview.service;

import com.interviewguide.pythonagent.mapper.PythonAgentMapper;
import com.interviewguide.pythonagent.domain.AgentCompleteRequest;
import com.interviewguide.pythonagent.domain.AgentInitializeRequest;
import com.interviewguide.pythonagent.domain.AgentRespondRequest;
import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.interview.domain.InterviewResponse;
import com.interviewguide.interview.domain.InterviewDetailResponse;
import com.interviewguide.interview.domain.InterviewTurnResponse;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.domain.InterviewSessionStatus;
import com.interviewguide.resume.domain.ResumeEntity;
import org.springframework.stereotype.Service;
import com.interviewguide.common.python.PythonAgentResponseValidator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Text interview application service that coordinates one public endpoint flow per public method. */
@Service
public class InterviewWorkflowService {
    private static final int AGENT_MAX_TOTAL_QUESTIONS = 20;
    private final InterviewSessionService sessionPersistence;
    private final PythonAgentMapper pythonAgentClient;
    private final InterviewKnowledgeBaseSelectionService knowledgeBaseSelection;
    private final InterviewResourceService accessUtil;
    private final InterviewResponseService responseUtil;
    private final PythonAgentResponseValidator pythonResponseUtil;

    /** Injects session persistence, Python integration and supporting internal interview services. */
    public InterviewWorkflowService(InterviewSessionService sessionPersistence,
                            PythonAgentMapper pythonAgentClient,
                            InterviewKnowledgeBaseSelectionService knowledgeBaseSelection,
                            InterviewResourceService accessUtil,
                            InterviewResponseService responseUtil,
                            PythonAgentResponseValidator pythonResponseUtil) {
        this.sessionPersistence = sessionPersistence;
        this.pythonAgentClient = pythonAgentClient;
        this.knowledgeBaseSelection = knowledgeBaseSelection;
        this.accessUtil = accessUtil;
        this.responseUtil = responseUtil;
        this.pythonResponseUtil = pythonResponseUtil;
    }

    /** Creates an owned session, initializes Python state and activates the persisted session. */
    public InterviewResponse start(String userId, String resumeId, String targetRole,
                                   int interviewDurationMinutes, String desiredDifficulty,
                                   String interviewDirection, String jdText,
                                   List<Map<String, Object>> customCategories) {
        ResumeEntity resume = accessUtil.ownedResume(resumeId, userId);
        CandidateEntity candidate = accessUtil.candidateForResume(resume);
        if (!resume.getId().equals(candidate.getCurrentResumeId())) {
            throw new com.interviewguide.common.exception.BusinessException("RESUME_NOT_CURRENT",
                    "a new interview can only be created from the candidate's current resume");
        }
        InterviewKnowledgeBaseSelectionService.Selection knowledgeBases = knowledgeBaseSelection.selectForUser(userId);
        String difficulty = switch (desiredDifficulty.strip().toUpperCase()) {
            case "EASY", "JUNIOR" -> "EASY";
            case "MEDIUM", "MID" -> "MEDIUM";
            case "HARD", "SENIOR" -> "HARD";
            default -> throw new com.interviewguide.common.exception.BusinessException(
                    "DIFFICULTY_INVALID", "difficulty must be EASY, MEDIUM or HARD");
        };
        String sessionId = UUID.randomUUID().toString();
        sessionPersistence.createConfigured(
                new InterviewSessionEntity(sessionId, userId, candidate.getId(), resume.getId(), null,
                        AGENT_MAX_TOTAL_QUESTIONS),
                interviewDirection, difficulty);

        String runId = UUID.randomUUID().toString();
        try {
            AgentResponse response = pythonAgentClient.initialize(new AgentInitializeRequest(
                    "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                    "agent.session.initialize",
                    new AgentInitializeRequest.CandidateSnapshot(
                            candidate.getId(), resume.getId(), null, resume.getContent(), jdText,
                            targetRole, interviewDurationMinutes, difficulty,
                            interviewDirection, customCategories,
                            knowledgeBases.systemKnowledgeBaseIds(), knowledgeBases.userKnowledgeBaseIds()),
                    Instant.now()));
            pythonResponseUtil.requireMatchingResponse(response, "AGENT_INITIALIZE_FAILED", userId, sessionId, runId);
            sessionPersistence.activate(sessionId, response);
            return responseUtil.fromSession(sessionPersistence.load(sessionId));
        } catch (RuntimeException error) {
            sessionPersistence.markFailed(sessionId);
            throw error;
        }
    }

    /** Sends one answer to Python and persists its next-question transition. */
    public InterviewResponse submitAnswer(String sessionId, String userId, String answer) {
        String runId = UUID.randomUUID().toString();
        InterviewSessionEntity session = accessUtil.ownedSession(sessionId, userId);
        if (session.getStatus() != InterviewSessionStatus.ACTIVE
                && session.getStatus() != InterviewSessionStatus.PAUSED) {
            throw new com.interviewguide.common.exception.BusinessException("SESSION_NOT_ACTIVE", "interview session is not active");
        }
        AgentResponse response = pythonAgentClient.respond(new AgentRespondRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.respond", session.getStatus().name(), session.getAgentStateVersion(), answer,
                Instant.now()));
        pythonResponseUtil.requireMatchingResponse(response, "AGENT_RESPONSE_FAILED", userId, sessionId, runId);
        sessionPersistence.applyAnswer(sessionId, session.getStateVersion(), runId, answer, response);
        return responseUtil.fromSession(sessionPersistence.load(sessionId));
    }

    /** Controller-facing read model; ownership is checked before exposing turns or Python progress. */
    /** Returns an owned session together with its ordered persisted answer turns. */
    public InterviewDetailResponse detail(String sessionId, String userId) {
        InterviewSessionEntity session = accessUtil.ownedSession(sessionId, userId);
        List<com.interviewguide.interview.domain.InterviewTurnEntity> persistedTurns = sessionPersistence.turns(sessionId);
        List<InterviewTurnResponse> indexedTurns = responseUtil.fromTurns(persistedTurns);
        return new InterviewDetailResponse(responseUtil.fromSession(session), indexedTurns);
    }

    /** Returns lower-agent progress only after the session ownership check succeeds. */
    public Map<String, Object> sessionProgress(String sessionId, String userId) {
        accessUtil.ownedSession(sessionId, userId);
        return pythonAgentClient.sessionProgress(sessionId);
    }

    /** Lists the caller's persisted sessions as public response records. */
    public List<InterviewResponse> list(String userId) {
        return sessionPersistence.list(userId).stream().map(responseUtil::fromSession).toList();
    }

    /** Finds the caller's latest unfinished session for one resume. */
    public InterviewResponse findUnfinished(String userId, String resumeId) {
        return sessionPersistence.findUnfinished(userId, resumeId).map(responseUtil::fromSession).orElse(null);
    }

    /** Completes an owned session through Python and persists its final evaluation. */
    public void complete(String sessionId, String userId) {
        InterviewSessionEntity session = accessUtil.ownedSession(sessionId, userId);
        if (session.getStatus() == InterviewSessionStatus.COMPLETED) return;
        String runId = UUID.randomUUID().toString();
        AgentResponse response = pythonAgentClient.complete(new AgentCompleteRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.session.complete", session.getStatus().name(), session.getAgentStateVersion(),
                Instant.now()));
        pythonResponseUtil.requireMatchingResponse(response, "AGENT_COMPLETE_FAILED", userId, sessionId, runId);
        sessionPersistence.completeFromAgent(sessionId, userId, response);
    }

    /** Pauses an owned active session after Python acknowledges the lifecycle transition. */
    public void pause(String sessionId, String userId) {
        InterviewSessionEntity session = accessUtil.ownedSession(sessionId, userId);
        if (session.getStatus() != InterviewSessionStatus.ACTIVE) return;
        String runId = UUID.randomUUID().toString();
        AgentResponse response = pythonAgentClient.complete(new AgentCompleteRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.session.pause", session.getStatus().name(), session.getAgentStateVersion(),
                Instant.now()));
        pythonResponseUtil.requireMatchingResponse(response, "AGENT_PAUSE_FAILED", userId, sessionId, runId);
        sessionPersistence.pauseFromAgent(sessionId, userId, response);
    }

    /** Completes active state when needed and removes the owned session and turns. */
    public void delete(String sessionId, String userId) {
        InterviewSessionEntity session = accessUtil.ownedSession(sessionId, userId);
        if (session.getStatus() == InterviewSessionStatus.ACTIVE || session.getStatus() == InterviewSessionStatus.PAUSED) {
            complete(sessionId, userId);
        }
        sessionPersistence.delete(sessionId, userId);
    }

}
