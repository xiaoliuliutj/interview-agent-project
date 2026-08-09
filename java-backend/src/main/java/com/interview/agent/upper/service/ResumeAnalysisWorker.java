package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.AgentGatewayException;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentResumeMemoryActivationRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.ResumeAnalysisRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import com.interview.agent.upper.config.RabbitTaskConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ResumeAnalysisWorker {
    private final AgentGateway agentGateway;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final RabbitTemplate rabbitTemplate;
    private final int maxDeliveryAttempts;

    public ResumeAnalysisWorker(
            AgentGateway agentGateway,
            ResumeAnalysisPersistenceService persistence,
            ResumeAnalysisRepository analysisRepository,
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxDeliveryAttempts) {
        this.agentGateway = agentGateway;
        this.persistence = persistence;
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
    }

    public void enqueue(Long analysisId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.RESUME_ANALYSIS, analysisId.toString(), userId));
    }

    public void process(Long analysisId, String userId) {
        var analysis = analysisRepository.findById(analysisId).orElse(null);
        // The user can upload a replacement or delete a resume after the message was sent.
        // Such a message is obsolete, not a retryable infrastructure failure.
        if (analysis == null || analysis.isCancelled()) {
            return;
        }
        ResumeEntity resume = resumeRepository.findById(analysis.getResumeId()).orElse(null);
        if (resume == null) {
            return;
        }
        if (!isCurrentResume(resume)) {
            persistence.cancel(analysisId);
            return;
        }
        var attempt = persistence.beginAttempt(analysisId);
        if (attempt == null) {
            return;
        }
        try {
            String activationRunId = "resume-memory-" + analysisId;
            String activationSessionId = "resume-memory-" + resume.getCandidateId();
            AgentResponse activation = agentGateway.activateResumeMemory(
                    new AgentResumeMemoryActivationRequest(
                            "v1", UUID.randomUUID().toString(), activationRunId,
                            userId, activationSessionId,
                            "agent.resume.activate", resume.getId(), resume.getCandidateId(),
                            resume.getContent(), analysis.getTargetRole(), Instant.now()));
            requireMatchingResponse(
                    activation, "lower resume memory activation failed",
                    userId, activationSessionId, activationRunId);
            if (!isCurrentResume(resume)) {
                persistence.cancel(analysisId);
                return;
            }
            String evaluationRunId = "resume-evaluation-" + analysisId;
            String evaluationSessionId = "resume-evaluation-" + analysisId;
            AgentResponse response = agentGateway.evaluateResume(
                    new AgentResumeEvaluateRequest(
                            "v1", UUID.randomUUID().toString(), evaluationRunId,
                            userId, evaluationSessionId,
                            "agent.resume.evaluate", "RESUME", resume.getId(),
                            resume.getCandidateId(), resume.getContent(), analysis.getTargetRole(), Instant.now()));
            if (response.code() < 100 || response.code() >= 200) {
                String message = response.error() == null ? "下层简历评价失败" : response.error().message();
                if (response.retryable()) {
                    throw new AgentGatewayException(message, null, true);
                }
                persistence.fail(analysisId, message);
                return;
            }
            requireMatchingResponse(
                    response, "lower resume evaluation response did not match request",
                    userId, evaluationSessionId, evaluationRunId);
            if (!persistence.isCancelled(analysisId) && isCurrentResume(resume)) {
                persistence.complete(analysisId, response);
            } else if (!persistence.isCancelled(analysisId)) {
                persistence.cancel(analysisId);
            }
        } catch (RuntimeException error) {
            if (persistence.isCancelled(analysisId)) {
                return;
            }
            if (isRetryable(error) && attempt.getRetryCount() < maxDeliveryAttempts) {
                persistence.recordRetryableFailure(analysisId, safeMessage(error));
                throw error;
            }
            if (!persistence.isCancelled(analysisId)) {
                persistence.fail(analysisId, safeMessage(error));
            }
        }
    }

    private boolean isRetryable(RuntimeException error) {
        return error instanceof AgentGatewayException gatewayError && gatewayError.retryable();
    }

    private boolean isCurrentResume(ResumeEntity resume) {
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId()).orElse(null);
        return candidate != null && resume.getId().equals(candidate.getCurrentResumeId());
    }

    private void requireSuccess(AgentResponse response, String fallbackMessage) {
        if (response == null || response.code() < 100 || response.code() >= 200) {
            String message = response != null && response.error() != null
                    ? response.error().message() : fallbackMessage;
            throw new AgentGatewayException(message, null, response != null && response.retryable());
        }
    }

    private void requireMatchingResponse(
            AgentResponse response, String fallbackMessage,
            String userId, String sessionId, String runId) {
        requireSuccess(response, fallbackMessage);
        if (!userId.equals(response.userId())
                || !sessionId.equals(response.sessionId())
                || !runId.equals(response.runId())) {
            throw new AgentGatewayException(
                    fallbackMessage + ": response identity mismatch", null, false);
        }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
