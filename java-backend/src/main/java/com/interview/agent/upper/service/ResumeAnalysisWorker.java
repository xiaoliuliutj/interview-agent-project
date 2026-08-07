package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.ResumeAnalysisRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import com.interview.agent.upper.config.RabbitTaskConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ResumeAnalysisWorker {
    private final AgentGateway agentGateway;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeRepository resumeRepository;
    private final RabbitTemplate rabbitTemplate;

    public ResumeAnalysisWorker(
            AgentGateway agentGateway,
            ResumeAnalysisPersistenceService persistence,
            ResumeAnalysisRepository analysisRepository,
            ResumeRepository resumeRepository,
            RabbitTemplate rabbitTemplate) {
        this.agentGateway = agentGateway;
        this.persistence = persistence;
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void enqueue(Long analysisId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.RESUME_ANALYSIS, analysisId.toString(), userId));
    }

    public void process(Long analysisId, String userId, String targetRole) {
        ResumeEntity resume = analysisRepository.findById(analysisId)
                .flatMap(analysis -> resumeRepository.findById(analysis.getResumeId()))
                .orElseThrow(() -> new BusinessException("RESUME_ANALYSIS_RESOURCE_NOT_FOUND", "简历或分析任务不存在"));
        persistence.markProcessing(analysisId);
        try {
            AgentResponse response = agentGateway.evaluateResume(
                            new AgentResumeEvaluateRequest(
                            "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            userId, "resume-evaluation-" + analysisId, resume.getId(),
                            resume.getCandidateId(), resume.getContent(), targetRole, List.of()));
            if (response.code() < 100 || response.code() >= 200) {
                String message = response.error() == null ? "下层简历评价失败" : response.error().message();
                persistence.fail(analysisId, message);
                throw new BusinessException("RESUME_ANALYSIS_AGENT_FAILED", message);
            }
            persistence.complete(analysisId, response);
        } catch (RuntimeException error) {
            persistence.fail(analysisId, safeMessage(error));
            throw error;
        }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
