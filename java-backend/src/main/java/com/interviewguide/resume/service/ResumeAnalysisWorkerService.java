package com.interviewguide.resume.service;

import com.interviewguide.common.exception.PythonAgentException;
import com.interviewguide.pythonagent.mapper.PythonAgentMapper;
import com.interviewguide.pythonagent.domain.AgentResumeEvaluateRequest;
import com.interviewguide.pythonagent.domain.AgentResumeMemoryActivationRequest;
import com.interviewguide.pythonagent.domain.AgentResponse;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateMapper;
import com.interviewguide.resume.mapper.ResumeAnalysisMapper;
import com.interviewguide.resume.mapper.ResumeMapper;
import com.interviewguide.common.messaging.AgentWorkTaskMessage;
import com.interviewguide.common.messaging.RabbitTaskConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.interviewguide.common.python.PythonAgentFailurePolicy;

import java.time.Instant;
import java.util.UUID;

/** Processes complete asynchronous resume-analysis jobs dispatched through RabbitMQ. */
@Service
public class ResumeAnalysisWorkerService {
    private final PythonAgentMapper pythonAgentClient;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisMapper analysisMapper;
    private final ResumeMapper resumeMapper;
    private final CandidateMapper candidateMapper;
    private final RabbitTemplate rabbitTemplate;
    private final int maxDeliveryAttempts;

    /** Injects Python integration, task persistence, mappers and RabbitMQ publication infrastructure. */
    public ResumeAnalysisWorkerService(
            PythonAgentMapper pythonAgentClient,
            ResumeAnalysisPersistenceService persistence,
            ResumeAnalysisMapper analysisMapper,
            ResumeMapper resumeMapper,
            CandidateMapper candidateMapper,
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxDeliveryAttempts) {
        this.pythonAgentClient = pythonAgentClient;
        this.persistence = persistence;
        this.analysisMapper = analysisMapper;
        this.resumeMapper = resumeMapper;
        this.candidateMapper = candidateMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
    }

    /** Publishes one durable analysis-task message after the task row has been created. */
    public void enqueue(Long analysisId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.RESUME_ANALYSIS, analysisId.toString(), userId));
    }

    /** Executes one queued task: validates currency, invokes Python and persists its terminal or retry state. */
    public void process(Long analysisId, String userId) {
        var analysis = analysisMapper.findById(analysisId).orElse(null);
        // The user can upload a replacement or delete a resume after the message was sent.
        // Such a message is obsolete, not a retryable infrastructure failure.
        if (analysis == null || analysis.isCancelled()) {
            return;
        }
        ResumeEntity resume = resumeMapper.findById(analysis.getResumeId()).orElse(null);
        if (resume == null) {
            return;
        }
        if (!candidateMapper.findById(resume.getCandidateId())
                .map(candidate -> resume.getId().equals(candidate.getCurrentResumeId())).orElse(false)) {
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
            AgentResponse activation = pythonAgentClient.activateResumeMemory(
                    new AgentResumeMemoryActivationRequest(
                            "v1", UUID.randomUUID().toString(), activationRunId,
                            userId, activationSessionId,
                            "agent.resume.activate", resume.getId(), resume.getCandidateId(),
                            resume.getContent(), analysis.getTargetRole(), Instant.now()));
            PythonAgentFailurePolicy.requireMatchingResponse(
                    activation, "lower resume memory activation failed",
                    userId, activationSessionId, activationRunId);
            if (!candidateMapper.findById(resume.getCandidateId())
                    .map(candidate -> resume.getId().equals(candidate.getCurrentResumeId())).orElse(false)) {
                persistence.cancel(analysisId);
                return;
            }
            String evaluationRunId = "resume-evaluation-" + analysisId;
            String evaluationSessionId = "resume-evaluation-" + analysisId;
            AgentResponse response = pythonAgentClient.evaluateResume(
                    new AgentResumeEvaluateRequest(
                            "v1", UUID.randomUUID().toString(), evaluationRunId,
                            userId, evaluationSessionId,
                            "agent.resume.evaluate", "RESUME", resume.getId(),
                            resume.getCandidateId(), resume.getContent(), analysis.getTargetRole(), Instant.now()));
            if (response.code() < 100 || response.code() >= 200) {
                String message = response.error() == null ? "lower resume evaluation failed" : response.error().message();
                if (response.retryable()) {
                    throw new PythonAgentException(message, null, true);
                }
                persistence.fail(analysisId, message);
                return;
            }
            PythonAgentFailurePolicy.requireMatchingResponse(
                    response, "lower resume evaluation response did not match request",
                    userId, evaluationSessionId, evaluationRunId);
            if (!persistence.isCancelled(analysisId)
                    && candidateMapper.findById(resume.getCandidateId())
                    .map(candidate -> resume.getId().equals(candidate.getCurrentResumeId())).orElse(false)) {
                persistence.complete(analysisId, response);
            } else if (!persistence.isCancelled(analysisId)) {
                persistence.cancel(analysisId);
            }
        } catch (RuntimeException error) {
            if (persistence.isCancelled(analysisId)) {
                return;
            }
            if (PythonAgentFailurePolicy.isRetryable(error) && attempt.getRetryCount() < maxDeliveryAttempts) {
                persistence.recordRetryableFailure(analysisId, PythonAgentFailurePolicy.safeMessage(error));
                throw error;
            }
            if (!persistence.isCancelled(analysisId)) {
                persistence.fail(analysisId, PythonAgentFailurePolicy.safeMessage(error));
            }
        }
    }

}
