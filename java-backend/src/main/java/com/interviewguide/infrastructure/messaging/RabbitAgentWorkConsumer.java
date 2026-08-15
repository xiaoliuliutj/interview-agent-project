package com.interviewguide.infrastructure.messaging;

import com.interviewguide.knowledgebase.service.KnowledgeBaseIndexWorker;
import com.interviewguide.resume.service.ResumeAnalysisWorker;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RabbitAgentWorkConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RabbitAgentWorkConsumer.class);
    private final ResumeAnalysisWorker resumeAnalysisWorker;
    private final KnowledgeBaseIndexWorker knowledgeBaseIndexWorker;

    public RabbitAgentWorkConsumer(ResumeAnalysisWorker resumeAnalysisWorker,
                                   KnowledgeBaseIndexWorker knowledgeBaseIndexWorker) {
        this.resumeAnalysisWorker = resumeAnalysisWorker;
        this.knowledgeBaseIndexWorker = knowledgeBaseIndexWorker;
    }

    @RabbitListener(queues = "${agent.rabbit.agent-work-queue:interview.agent.work.execute}")
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
                        knowledgeBaseIndexWorker.process(message.resourceId(), message.userId());
                default -> logger.error("Discarding unsupported Agent work type: {}", message.taskType());
            }
        } catch (NumberFormatException error) {
            logger.error("Discarding Agent work message with invalid resource ID: {}", message.resourceId());
        }
    }
}
