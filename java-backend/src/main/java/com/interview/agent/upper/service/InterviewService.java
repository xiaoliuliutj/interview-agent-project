package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentInitializeRequest;
import com.interview.agent.upper.agent.dto.AgentRespondRequest;
import com.interview.agent.upper.agent.dto.AgentCompleteRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.api.dto.InterviewView;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.JobDescriptionEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.domain.InterviewSessionStatus;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.JobDescriptionRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.UUID;
import java.util.List;

@Service
public class InterviewService {
    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final InterviewSessionPersistenceService sessionPersistence;
    private final AgentGateway agentGateway;
    private final String defaultTargetRole;

    public InterviewService(
            CandidateRepository candidateRepository,
            ResumeRepository resumeRepository,
            JobDescriptionRepository jobDescriptionRepository,
            InterviewSessionPersistenceService sessionPersistence,
            AgentGateway agentGateway,
            @Value("${agent.default-target-role:Java 后端}") String defaultTargetRole) {
        this.candidateRepository = candidateRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.sessionPersistence = sessionPersistence;
        this.agentGateway = agentGateway;
        this.defaultTargetRole = defaultTargetRole;
    }

    public InterviewView start(CreateInterviewRequest request) {
        CandidateEntity candidate = requireCandidate(request);
        ResumeEntity resume = requireResume(request.resumeId(), candidate.getId());
        JobDescriptionEntity jd = requireJobDescription(request.jdId());
        String sessionId = UUID.randomUUID().toString();
        sessionPersistence.create(new InterviewSessionEntity(
                sessionId, request.userId(), candidate.getId(), resume.getId(),
                jd == null ? null : jd.getId(),
                request.totalQuestions() == null ? 6 : request.totalQuestions()));

        AgentResponse response;
        try {
            response = agentGateway.initialize(toAgentRequest(request, sessionId, candidate, resume, jd));
        } catch (RuntimeException error) {
            sessionPersistence.markFailed(sessionId);
            throw error;
        }
        if (!isSuccessful(response)) {
            sessionPersistence.markFailed(sessionId);
            throw new BusinessException("AGENT_INITIALIZE_FAILED", "下层 Agent 初始化失败");
        }
        sessionPersistence.activate(sessionId, response);
        return toView(sessionPersistence.load(sessionId));
    }

    public InterviewView submitAnswer(
            String sessionId, String userId, String answer, String requestedRunId) {
        InterviewSessionEntity session = sessionPersistence.load(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "无权访问该面试会话");
        }
        if (session.getStatus() != InterviewSessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_NOT_ACTIVE", "当前面试会话不能继续回答");
        }
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedRunId;
        AgentResponse response = agentGateway.respond(new AgentRespondRequest(
                "v1", UUID.randomUUID().toString(), runId, userId, sessionId, answer));
        if (!isSuccessful(response)) {
            throw new BusinessException("AGENT_RESPONSE_FAILED", "下层 Agent 处理失败");
        }
        sessionPersistence.applyAnswer(
                sessionId, session.getStateVersion(), runId, answer, response);
        return toView(sessionPersistence.load(sessionId));
    }

    public InterviewView view(String sessionId) {
        return toView(sessionPersistence.load(sessionId));
    }

    public List<InterviewView> list(String userId) {
        return sessionPersistence.list(userId).stream().map(this::toView).toList();
    }

    public InterviewView findUnfinished(String userId, String resumeId) {
        return sessionPersistence.findUnfinished(userId, resumeId)
                .map(this::toView).orElse(null);
    }

    public void saveDraft(String sessionId, String userId, String answer) {
        sessionPersistence.saveDraft(sessionId, userId, answer);
    }

    public void complete(String sessionId, String userId) {
        InterviewSessionEntity session = sessionPersistence.load(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "无权访问该面试会话");
        }
        if (session.getStatus() == InterviewSessionStatus.COMPLETED) {
            return;
        }
        AgentResponse response = agentGateway.complete(new AgentCompleteRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                userId, sessionId));
        if (!isSuccessful(response)) {
            throw new BusinessException("AGENT_COMPLETE_FAILED", "下层 Agent 会话关闭失败");
        }
        sessionPersistence.complete(sessionId, userId);
    }

    public void delete(String sessionId, String userId) {
        InterviewSessionEntity session = sessionPersistence.load(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "无权访问该面试会话");
        }
        if (session.getStatus() != InterviewSessionStatus.COMPLETED) {
            complete(sessionId, userId);
        }
        sessionPersistence.delete(sessionId, userId);
    }

    public List<com.interview.agent.upper.domain.InterviewTurnEntity> turns(String sessionId) {
        return sessionPersistence.turns(sessionId);
    }

    private CandidateEntity requireCandidate(CreateInterviewRequest request) {
        CandidateEntity candidate = candidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new BusinessException("CANDIDATE_NOT_FOUND", "候选人不存在"));
        if (!candidate.getUserId().equals(request.userId())) {
            throw new BusinessException("CANDIDATE_ACCESS_DENIED", "候选人不属于当前用户");
        }
        return candidate;
    }

    private ResumeEntity requireResume(String resumeId, String candidateId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException("RESUME_NOT_FOUND", "简历不存在"));
        if (!resume.getCandidateId().equals(candidateId)) {
            throw new BusinessException("RESUME_CANDIDATE_MISMATCH", "简历与候选人不匹配");
        }
        return resume;
    }

    private JobDescriptionEntity requireJobDescription(String jdId) {
        if (jdId == null) {
            return null;
        }
        return jobDescriptionRepository.findById(jdId)
                .orElseThrow(() -> new BusinessException("JD_NOT_FOUND", "JD 不存在"));
    }

    private AgentInitializeRequest toAgentRequest(
            CreateInterviewRequest request,
            String sessionId,
            CandidateEntity candidate,
            ResumeEntity resume,
            JobDescriptionEntity jd) {
        return new AgentInitializeRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                request.userId(), sessionId,
                new AgentInitializeRequest.CandidateSnapshot(
                        candidate.getId(), resume.getId(), jd == null ? null : jd.getId(),
                        resume.getContent(), jd == null ? "" : jd.getContent(),
                        jd == null ? defaultTargetRole : jd.getTitle(), 40, "MEDIUM"));
    }

    private boolean isSuccessful(AgentResponse response) {
        return response.code() >= 100 && response.code() < 200;
    }

    private InterviewView toView(InterviewSessionEntity session) {
        return new InterviewView(
                session.getId(), session.getUserId(), session.getCandidateId(),
                session.getResumeId(), session.getJdId(), session.getTotalQuestions(),
                session.getStatus().name(), session.getStateVersion(),
                session.getCurrentQuestion(), session.getCreatedAt(), session.getUpdatedAt());
    }
}
