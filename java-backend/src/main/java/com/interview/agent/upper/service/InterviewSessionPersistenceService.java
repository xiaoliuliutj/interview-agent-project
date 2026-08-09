package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.InterviewSessionStatus;
import com.interview.agent.upper.domain.InterviewTurnEntity;
import com.interview.agent.upper.repository.InterviewSessionRepository;
import com.interview.agent.upper.repository.InterviewTurnRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class InterviewSessionPersistenceService {
    private static final Set<String> AGENT_STAGES = Set.of(
            "OPENING", "PROJECT", "FUNDAMENTAL", "SCENARIO", "CODING", "SUMMARY");

    private final InterviewSessionRepository sessionRepository;
    private final InterviewTurnRepository turnRepository;

    public InterviewSessionPersistenceService(
            InterviewSessionRepository sessionRepository,
            InterviewTurnRepository turnRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
    }

    @Transactional
    public InterviewSessionEntity createConfigured(
            InterviewSessionEntity session, String skillId, String difficulty) {
        session.configure(skillId, difficulty);
        return sessionRepository.save(session);
    }

    @Transactional
    public void activate(String sessionId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        sessionRepository.save(session);
    }

    @Transactional
    public void applyAnswer(
            String sessionId,
            long expectedVersion,
            String runId,
            String candidateAnswer,
        AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        Optional<InterviewTurnEntity> existingTurn = turnRepository.findByRunId(runId);
        if (existingTurn.isPresent()) {
            InterviewTurnEntity persisted = existingTurn.get();
            if (!sessionId.equals(persisted.getSessionId())
                    || !candidateAnswer.equals(persisted.getCandidateAnswer())) {
                throw new BusinessException(
                        "RUN_ID_PAYLOAD_MISMATCH", "runId has already been used with a different interview answer");
            }
            return;
        }

        // A response can be retried after the lower layer has already completed the
        // run and this transaction has already advanced the JPA version.  The runId
        // check above must therefore precede optimistic-version validation.  A new
        // runId with an old version is still a genuine concurrent submission.
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
        turnRepository.save(turn);
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        sessionRepository.save(session);
    }

    @Transactional
    public void markFailed(String sessionId) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        session.applyAgentResponse(null, "FAILED");
        sessionRepository.save(session);
    }

    @Transactional
    public void completeFromAgent(String sessionId, String userId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        session.complete();
        sessionRepository.save(session);
    }

    @Transactional
    public void pauseFromAgent(String sessionId, String userId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        session.applyAgentResponse(response.answer(), response.sessionStatus(), response.stateVersion(), response.currentStage());
        sessionRepository.save(session);
    }

    @Transactional
    public void delete(String sessionId, String userId) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        turnRepository.deleteAll(turnRepository.findBySessionIdOrderByCreatedAt(sessionId));
        sessionRepository.delete(session);
    }

    public List<InterviewSessionEntity> list(String userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<InterviewSessionEntity> findUnfinished(String userId, String resumeId) {
        return sessionRepository.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
                userId,
                resumeId,
                List.of(InterviewSessionStatus.INITIALIZING,
                        InterviewSessionStatus.ACTIVE,
                        InterviewSessionStatus.PAUSED));
    }

    public List<InterviewTurnEntity> turns(String sessionId) {
        return turnRepository.findBySessionIdOrderByCreatedAt(sessionId);
    }

    public InterviewSessionEntity load(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
    }

    private InterviewSessionEntity requiredForUpdate(String sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "interview session not found"));
    }

    private void assertOwner(InterviewSessionEntity session, String userId) {
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "interview session does not belong to current user");
        }
    }
}
