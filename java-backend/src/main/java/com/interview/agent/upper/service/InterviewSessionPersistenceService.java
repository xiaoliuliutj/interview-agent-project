package com.interview.agent.upper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public InterviewSessionPersistenceService(
            InterviewSessionRepository sessionRepository,
            InterviewTurnRepository turnRepository,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InterviewSessionEntity create(InterviewSessionEntity session) {
        return sessionRepository.save(session);
    }

    @Transactional
    public InterviewSessionEntity saveConfiguration(InterviewSessionEntity session) {
        return sessionRepository.save(session);
    }

    @Transactional
    public void activate(String sessionId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        session.applyAgentResponse(response.answer(), response.sessionStatus());
        applyFinalEvaluation(session, response);
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
        Object answerSummary = response.output() == null ? null : response.output().get("answerSummary");
        Object score = response.output() == null ? null : response.output().get("score");
        Object strengths = response.output() == null ? null : response.output().get("strengths");
        Object weaknesses = response.output() == null ? null : response.output().get("weaknesses");
        Object preferences = response.output() == null ? null : response.output().get("preferences");
        if (!(score instanceof Number) || !(answerSummary instanceof String) || ((String) answerSummary).isBlank()) {
            throw new BusinessException("AGENT_EVALUATION_OUTPUT_INVALID", "下层回答评估缺少分数或摘要");
        }
        Object stage = response.output() == null ? null : response.output().get("stage");
        if (!(stage instanceof String value) || value.isBlank()) {
            throw new BusinessException("AGENT_EVALUATION_OUTPUT_INVALID", "下层回答评估缺少阶段");
        }
        InterviewTurnEntity turn = new InterviewTurnEntity(
                sessionId, runId, session.getCurrentQuestion(), candidateAnswer, evaluationSummary,
                ((Number) score).intValue(),
                (String) answerSummary,
                json(strengths), json(weaknesses), json(preferences));
        turn.setStage((String) stage);
        turnRepository.save(turn);
        session.applyAgentResponse(response.answer(), response.sessionStatus());
        applyFinalEvaluation(session, response);
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
    public void completeFromAgent(String sessionId, String userId, AgentResponse response) {
        InterviewSessionEntity session = requiredForUpdate(sessionId);
        assertOwner(session, userId);
        applyFinalEvaluation(session, response);
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

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException error) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private void applyFinalEvaluation(InterviewSessionEntity session, AgentResponse response) {
        if (response.output() == null) return;
        Object raw = response.output().get("finalEvaluation");
        if (!(raw instanceof java.util.Map<?, ?> map)) return;
        Object score = map.get("overallScore");
        Object summary = map.get("summary");
        session.applyFinalEvaluation(score instanceof Number number ? number.intValue() : null,
                summary instanceof String text ? text : response.answer());
    }
}
