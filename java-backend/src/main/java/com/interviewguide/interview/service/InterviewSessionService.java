package com.interviewguide.interview.service;

import com.interviewguide.common.exception.BusinessException;

import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.interview.domain.InterviewSessionEntity;
import com.interviewguide.interview.domain.InterviewSessionStatus;
import com.interviewguide.interview.domain.InterviewTurnEntity;
import com.interviewguide.interview.mapper.InterviewSessionMapper;
import com.interviewguide.interview.mapper.InterviewTurnMapper;
import org.springframework.transaction.annotation.Transactional;
import com.interviewguide.utils.json.AgentOutputUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

/** Maintains the complete durable lifecycle and turn history of interview sessions. */
@Service
public class InterviewSessionService {
    private static final Set<String> AGENT_STAGES = Set.of(
            "OPENING", "PROJECT", "FUNDAMENTAL", "SCENARIO", "CODING", "SUMMARY");

    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final AgentOutputUtil outputUtil;

    /** Injects the two interview mappers and the generic Agent-output JSON utility. */
    public InterviewSessionService(
            InterviewSessionMapper sessionMapper,
            InterviewTurnMapper turnMapper,
            AgentOutputUtil outputUtil) {
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.outputUtil = outputUtil;
    }

    @Transactional
    /** Configures and persists a newly created initializing session. */
    public InterviewSessionEntity createConfigured(
            InterviewSessionEntity session, String interviewDirection, String difficulty) {
        session.configure(interviewDirection, difficulty);
        return sessionMapper.save(session);
    }

    @Transactional
    /** Applies the initial Python response and activates a locked session. */
    public void activate(String sessionId, AgentResponse response) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        if (response.output() != null) {
            session.applyCounters(
                    response.output().get("totalQuestionCount") instanceof Number value ? value.intValue() : null,
                    response.output().get("currentPrimaryQuestionCount") instanceof Number value ? value.intValue() : null,
                    response.output().get("totalPrimaryQuestionCount") instanceof Number value ? value.intValue() : null,
                    response.output().get("currentFollowupCount") instanceof Number value ? value.intValue() : null);
        }
        session.advanceStateVersion();
        sessionMapper.save(session);
    }

    @Transactional
    /** Persists one answer turn and the next Python-provided session state. */
    public void applyAnswer(
            String sessionId,
            long expectedVersion,
            String runId,
            String candidateAnswer,
        AgentResponse response) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        // Reject concurrent session mutations without exposing an idempotency token to clients.
        if (session.getStateVersion() != expectedVersion) {
            throw new BusinessException(
                    "SESSION_CONCURRENT_MODIFICATION", "interview session was changed by another request");
        }

        String turnStage = response.turnStage();
        if (turnStage == null || turnStage.isBlank()) {
            throw new BusinessException(
                    "AGENT_TURN_STAGE_MISSING", "lower Agent response did not include the answered stage");
        }
        if (!AGENT_STAGES.contains(turnStage)) {
            throw new BusinessException(
                    "AGENT_TURN_STAGE_INVALID", "lower Agent returned an unsupported answered stage");
        }

        InterviewTurnEntity turn = new InterviewTurnEntity(
                sessionId, runId, session.getCurrentQuestion(), candidateAnswer);
        turn.setStage(turnStage);
        Map<String, Object> output = response.output();
        if (output != null) {
            Object summary = output.get("evaluationSummary");
            if (summary instanceof String value) turn.setEvaluationSummary(value);
            Object score = output.get("evaluationScore");
            if (score instanceof Number value) turn.setScore(value.intValue());
            try {
                if (output.get("strengths") != null) turn.setStrengthsJson(outputUtil.json(output.get("strengths")));
                if (output.get("weaknesses") != null) turn.setWeaknessesJson(outputUtil.json(output.get("weaknesses")));
                if (output.get("finalEvaluation") != null) session.setFinalEvaluationJson(outputUtil.json(output.get("finalEvaluation")));
            } catch (RuntimeException ignored) {
                // Candidate-visible fields remain available even if optional report JSON cannot be serialized.
            }
            session.applyCounters(
                    output.get("totalQuestionCount") instanceof Number value ? value.intValue() : null,
                    output.get("currentPrimaryQuestionCount") instanceof Number value ? value.intValue() : null,
                    output.get("totalPrimaryQuestionCount") instanceof Number value ? value.intValue() : null,
                    output.get("currentFollowupCount") instanceof Number value ? value.intValue() : null);
        }
        turnMapper.save(turn);
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        session.advanceStateVersion();
        sessionMapper.save(session);
    }

    @Transactional
    /** Marks a session failed when initialization or an agent call terminates unexpectedly. */
    public void markFailed(String sessionId) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        session.applyAgentResponse(null, "FAILED");
        session.advanceStateVersion();
        sessionMapper.save(session);
    }

    @Transactional
    /** Applies a Python completion response to an owned locked session. */
    public void completeFromAgent(String sessionId, String userId, AgentResponse response) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "interview session does not belong to current user");
        }
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        if (response.output() != null && response.output().get("finalEvaluation") != null) {
            session.setFinalEvaluationJson(outputUtil.json(response.output().get("finalEvaluation")));
        }
        session.complete();
        session.advanceStateVersion();
        sessionMapper.save(session);
    }

    @Transactional
    /** Applies a Python pause response to an owned locked session. */
    public void pauseFromAgent(String sessionId, String userId, AgentResponse response) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "interview session does not belong to current user");
        }
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        session.advanceStateVersion();
        sessionMapper.save(session);
    }

    @Transactional
    /** Removes all turns and the owned session in one transaction. */
    public void delete(String sessionId, String userId) {
        InterviewSessionEntity session = sessionMapper.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "interview session does not belong to current user");
        }
        List<InterviewTurnEntity> turns = turnMapper.findBySessionIdOrderByCreatedAt(sessionId);
        if (!turns.isEmpty()) turnMapper.deleteAll(turns);
        sessionMapper.delete(session);
    }

    /** Lists sessions belonging to one user. */
    public List<InterviewSessionEntity> list(String userId) {
        return sessionMapper.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Finds the newest active, paused or initializing session for one user and resume. */
    public Optional<InterviewSessionEntity> findUnfinished(String userId, String resumeId) {
        return sessionMapper.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
                userId,
                resumeId,
                List.of(InterviewSessionStatus.INITIALIZING,
                        InterviewSessionStatus.ACTIVE,
                        InterviewSessionStatus.PAUSED));
    }

    /** Lists persisted turns for one session in creation order. */
    public List<InterviewTurnEntity> turns(String sessionId) {
        return turnMapper.findBySessionIdOrderByCreatedAt(sessionId);
    }

    /** Loads a session or raises the stable not-found business exception. */
    public InterviewSessionEntity load(String sessionId) {
        return sessionMapper.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
    }

}
