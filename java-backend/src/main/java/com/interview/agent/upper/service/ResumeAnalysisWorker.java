package com.interview.agent.upper.service;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResumeEvaluateRequest;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.domain.ResumeEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ResumeAnalysisWorker {
    private final AgentGateway agentGateway;
    private final ResumeAnalysisPersistenceService persistence;

    public ResumeAnalysisWorker(
            AgentGateway agentGateway, ResumeAnalysisPersistenceService persistence) {
        this.agentGateway = agentGateway;
        this.persistence = persistence;
    }

    @Async("interviewTaskExecutor")
    public void evaluate(
            Long analysisId, String userId, ResumeEntity resume, String targetRole) {
        persistence.markProcessing(analysisId);
        try {
            AgentResponse response = agentGateway.evaluateResume(
                    new AgentResumeEvaluateRequest(
                            "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            userId, "resume-evaluation-" + analysisId, resume.getId(),
                            resume.getContent(), targetRole, List.of()));
            if (response.code() < 100 || response.code() >= 200) {
                persistence.fail(analysisId, response.error() == null
                        ? "下层简历评价失败" : response.error().message());
                return;
            }
            persistence.complete(analysisId, response);
        } catch (RuntimeException error) {
            persistence.fail(analysisId, safeMessage(error));
        }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
