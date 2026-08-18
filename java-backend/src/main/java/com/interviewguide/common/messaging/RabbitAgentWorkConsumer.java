package com.interviewguide.common.messaging;

import com.interviewguide.knowledgebase.service.KnowledgeBaseLifecycleService;
import com.interviewguide.resume.service.ResumeAnalysisWorkerService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Consumes RabbitMQ Agent work messages and delegates them to complete task services. */
@Component
public class RabbitAgentWorkConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RabbitAgentWorkConsumer.class);
    private final ResumeAnalysisWorkerService resumeAnalysisWorker;
    private final KnowledgeBaseLifecycleService knowledgeBaseIndexWorker;

    /** Injects resume-analysis and knowledge-base-index task processors. */
    public RabbitAgentWorkConsumer(ResumeAnalysisWorkerService resumeAnalysisWorker,
                                   KnowledgeBaseLifecycleService knowledgeBaseIndexWorker) {
        this.resumeAnalysisWorker = resumeAnalysisWorker;
        this.knowledgeBaseIndexWorker = knowledgeBaseIndexWorker;
    }

    @RabbitListener(queues = "${agent.rabbit.agent-work-queue:interview.agent.work.execute}")
    /** Routes valid messages by task type and discards malformed message payloads. */
    public void consume(AgentWorkTaskMessage message) {
        if (message == null || message.taskType() == null || message.resourceId() == null || message.userId() == null) {
            logger.error("Discarding invalid Agent work message: {}", message);
            return;
        }
        try {
            switch (message.taskType()) {
                case AgentWorkTaskMessage.RESUME_ANALYSIS ->
                        resumeAnalysisWorker.process(Long.parseLong(message.resourceId()), message.userId());
                case AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX ->
                        knowledgeBaseIndexWorker.processIndex(message.resourceId(), message.userId());
                default -> logger.error("Discarding unsupported Agent work type: {}", message.taskType());
            }
        } catch (NumberFormatException error) {
            logger.error("Discarding Agent work message with invalid resource ID: {}", message.resourceId());
        }
    }
}
