package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentCompleteRequest;
import com.interview.agent.upper.agent.dto.AgentInitializeRequest;
import com.interview.agent.upper.agent.dto.AgentRespondRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.api.dto.InterviewView;
import com.interview.agent.upper.api.dto.StartInterviewRequest;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.InterviewSessionStatus;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/** Text interview application service. All external requests use the single public DTO. */
@Service
public class InterviewService {
    private static final int AGENT_MAX_TOTAL_QUESTIONS = 20;
    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final InterviewSessionPersistenceService sessionPersistence;
    private final AgentGateway agentGateway;
    private final InterviewKnowledgeBaseSelectionService knowledgeBaseSelection;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewService(CandidateRepository candidateRepository,
                            ResumeRepository resumeRepository,
                            InterviewSessionPersistenceService sessionPersistence,
                            AgentGateway agentGateway,
                            InterviewKnowledgeBaseSelectionService knowledgeBaseSelection) {
        this.candidateRepository = candidateRepository;
        this.resumeRepository = resumeRepository;
        this.sessionPersistence = sessionPersistence;
        this.agentGateway = agentGateway;
        this.knowledgeBaseSelection = knowledgeBaseSelection;
    }

    public InterviewView start(String userId, StartInterviewRequest request) {
        ResumeEntity resume = ownedResume(request.resumeId(), userId);
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
        if (!resume.getId().equals(candidate.getCurrentResumeId())) {
            throw new BusinessException("RESUME_NOT_CURRENT",
                    "a new interview can only be created from the candidate's current resume");
        }
        InterviewKnowledgeBaseSelectionService.Selection knowledgeBases = knowledgeBaseSelection.selectForUser(userId);
        String difficulty = normalizeDifficulty(request.desiredDifficulty());
        String sessionId = UUID.randomUUID().toString();
        sessionPersistence.createConfigured(
                new InterviewSessionEntity(sessionId, userId, candidate.getId(), resume.getId(), null,
                        AGENT_MAX_TOTAL_QUESTIONS),
                request.skillId(), difficulty);

        String runId = UUID.randomUUID().toString();
        try {
            AgentResponse response = agentGateway.initialize(new AgentInitializeRequest(
                    "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                    "agent.session.initialize",
                    new AgentInitializeRequest.CandidateSnapshot(
                            candidate.getId(), resume.getId(), null, resume.getContent(), request.jdText(),
                            request.targetRole(), request.interviewDurationMinutes(), difficulty,
                            request.skillId(), request.customCategories(),
                            knowledgeBases.systemKnowledgeBaseIds(), knowledgeBases.userKnowledgeBaseIds()),
                    Instant.now()));
            requireMatchingResponse(response, "AGENT_INITIALIZE_FAILED", userId, sessionId, runId);
            sessionPersistence.activate(sessionId, response);
            return toView(sessionPersistence.load(sessionId));
        } catch (RuntimeException error) {
            sessionPersistence.markFailed(sessionId);
            throw error;
        }
    }

    public InterviewView submitAnswer(String sessionId, String userId, String answer, String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException("RUN_ID_REQUIRED", "runId is required for an idempotent answer submission");
        }
        InterviewSessionEntity session = ownedSession(sessionId, userId);
        if (session.getStatus() != InterviewSessionStatus.ACTIVE
                && session.getStatus() != InterviewSessionStatus.PAUSED) {
            throw new BusinessException("SESSION_NOT_ACTIVE", "interview session is not active");
        }
        AgentResponse response = agentGateway.respond(new AgentRespondRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.respond", session.getStatus().name(), session.getAgentStateVersion(), answer,
                Instant.now()));
        requireMatchingResponse(response, "AGENT_RESPONSE_FAILED", userId, sessionId, runId);
        sessionPersistence.applyAnswer(sessionId, session.getStateVersion(), runId, answer, response);
        return toView(sessionPersistence.load(sessionId));
    }

    public InterviewView view(String sessionId) {
        return toView(sessionPersistence.load(sessionId));
    }

    public List<InterviewView> list(String userId) {
        return sessionPersistence.list(userId).stream().map(this::toView).toList();
    }

    public InterviewView findUnfinished(String userId, String resumeId) {
        return sessionPersistence.findUnfinished(userId, resumeId).map(this::toView).orElse(null);
    }

    public void complete(String sessionId, String userId) {
        InterviewSessionEntity session = ownedSession(sessionId, userId);
        if (session.getStatus() == InterviewSessionStatus.COMPLETED) return;
        String runId = UUID.randomUUID().toString();
        AgentResponse response = agentGateway.complete(new AgentCompleteRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.session.complete", session.getStatus().name(), session.getAgentStateVersion(),
                Instant.now()));
        requireMatchingResponse(response, "AGENT_COMPLETE_FAILED", userId, sessionId, runId);
        sessionPersistence.completeFromAgent(sessionId, userId, response);
    }

    public void pause(String sessionId, String userId) {
        InterviewSessionEntity session = ownedSession(sessionId, userId);
        if (session.getStatus() != InterviewSessionStatus.ACTIVE) return;
        String runId = UUID.randomUUID().toString();
        AgentResponse response = agentGateway.complete(new AgentCompleteRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId,
                "agent.session.pause", session.getStatus().name(), session.getAgentStateVersion(),
                Instant.now()));
        requireMatchingResponse(response, "AGENT_PAUSE_FAILED", userId, sessionId, runId);
        sessionPersistence.pauseFromAgent(sessionId, userId, response);
    }

    public void delete(String sessionId, String userId) {
        InterviewSessionEntity session = ownedSession(sessionId, userId);
        if (session.getStatus() == InterviewSessionStatus.ACTIVE || session.getStatus() == InterviewSessionStatus.PAUSED) {
            complete(sessionId, userId);
        }
        sessionPersistence.delete(sessionId, userId);
    }

    public List<com.interview.agent.upper.domain.InterviewTurnEntity> turns(String sessionId) {
        return sessionPersistence.turns(sessionId);
    }

    private ResumeEntity ownedResume(String resumeId, String userId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "resume not found"));
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "candidate not found"));
        if (!userId.equals(candidate.getUserId())) {
            throw new BusinessException("RESUME_ACCESS_DENIED", "resume does not belong to current user");
        }
        return resume;
    }

    private InterviewSessionEntity ownedSession(String sessionId, String userId) {
        InterviewSessionEntity session = sessionPersistence.load(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        return session;
    }

    public static String normalizeDifficulty(String value) {
        return switch (value.strip().toUpperCase()) {
            case "EASY", "JUNIOR" -> "EASY";
            case "MEDIUM", "MID" -> "MEDIUM";
            case "HARD", "SENIOR" -> "HARD";
            default -> throw new BusinessException("DIFFICULTY_INVALID", "difficulty must be EASY, MEDIUM or HARD");
        };
    }

    private static void requireSuccess(AgentResponse response, String errorCode) {
        if (response == null || response.code() < 100 || response.code() >= 200) {
            String message = response != null && response.error() != null
                    ? response.error().message() : "lower agent processing failed";
            String type = response != null && response.error() != null && response.error().type() != null
                    ? response.error().type() : errorCode;
            boolean retryable = response != null && response.error() != null && response.error().retryable();
            String stage = response == null ? "AGENT_CALL"
                    : firstNonBlank(response.turnStage(), response.currentStage(), response.status());
            throw new BusinessException(type, message, retryable,
                    retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                    response == null ? null : response.requestId(),
                    response == null ? null : response.runId(),
                    response == null ? null : response.sessionId(), stage);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static void requireMatchingResponse(
            AgentResponse response, String errorCode, String userId, String sessionId, String runId) {
        requireSuccess(response, errorCode);
        if (!userId.equals(response.userId())
                || !sessionId.equals(response.sessionId())
                || !runId.equals(response.runId())) {
            throw new BusinessException(
                    "AGENT_RESPONSE_IDENTITY_MISMATCH",
                    "lower Agent response does not match the submitted session or run");
        }
    }

    private InterviewView toView(InterviewSessionEntity session) {
        Map<String, Object> finalEvaluation = parseFinalEvaluation(session.getFinalEvaluationJson());
        return new InterviewView(session.getId(), session.getUserId(), session.getCandidateId(), session.getResumeId(),
                session.getJdId(), session.getSkillId(), session.getDifficulty(), session.getTotalQuestions(),
                session.getStatus().name(), session.getAgentStateVersion(), session.getCurrentQuestion(),
                session.getCurrentStage(), session.getIssuedQuestionCount(), session.getPrimaryQuestionCount(),
                session.getTotalPrimaryQuestionCount(),
                session.getFollowupCount(), finalEvaluation,
                session.getCreatedAt(), session.getUpdatedAt());
    }

    private Map<String, Object> parseFinalEvaluation(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
