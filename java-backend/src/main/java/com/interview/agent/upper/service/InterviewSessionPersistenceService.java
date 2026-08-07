package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.InterviewTurnEntity;
import com.interview.agent.upper.domain.InterviewSessionStatus;
import com.interview.agent.upper.repository.InterviewSessionRepository;
import com.interview.agent.upper.repository.InterviewTurnRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class InterviewSessionPersistenceService {
    private final InterviewSessionRepository sessionRepository;
    private final InterviewTurnRepository turnRepository;

    public InterviewSessionPersistenceService(
            InterviewSessionRepository sessionRepository,
            InterviewTurnRepository turnRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
    }

    @Transactional
    public InterviewSessionEntity create(InterviewSessionEntity session) {
        return sessionRepository.save(session);
    }

    @Transactional
    public void activate(String sessionId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        session.applyAgentResponse(response.answer(), response.sessionStatus());
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
        if (session.getStateVersion() != expectedVersion) {
            throw new BusinessException("SESSION_CONCURRENT_MODIFICATION", "面试会话已被其他请求修改");
        }
        if (turnRepository.existsByRunId(runId)) {
            return;
        }
        Object evaluation = response.output() == null
                ? null
                : response.output().get("evaluationSummary");
        String evaluationSummary = evaluation instanceof String value ? value : null;
        turnRepository.save(new InterviewTurnEntity(
                sessionId, runId, session.getCurrentQuestion(), candidateAnswer, evaluationSummary));
        session.applyAgentResponse(response.answer(), response.sessionStatus());
        sessionRepository.save(session);
    }

    @Transactional
    public void markFailed(String sessionId) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        session.applyAgentResponse(null, "FAILED");
        sessionRepository.save(session);
    }

    @Transactional
    public void saveDraft(String sessionId, String userId, String answer) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        if (session.getStatus() == InterviewSessionStatus.COMPLETED) {
            throw new BusinessException("SESSION_ALREADY_COMPLETED", "面试已经结束");
        }
        session.saveDraft(answer);
        sessionRepository.save(session);
    }

    @Transactional
    public void complete(String sessionId, String userId) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        session.complete();
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
                userId, resumeId, List.of(InterviewSessionStatus.INITIALIZING,
                        InterviewSessionStatus.ACTIVE, InterviewSessionStatus.PAUSED));
    }

    public List<InterviewTurnEntity> turns(String sessionId) {
        return turnRepository.findBySessionIdOrderByCreatedAt(sessionId);
    }

    public InterviewSessionEntity load(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "面试会话不存在"));
    }

    private InterviewSessionEntity requiredForUpdate(String sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "面试会话不存在"));
    }

    private void assertOwner(InterviewSessionEntity session, String userId) {
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "无权访问该面试会话");
        }
    }
}
