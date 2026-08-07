package com.interview.agent.upper.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 简历评价和知识库向量化共用可靠任务队列；消费者只根据资源 ID 重新加载数据库数据。
 */
@Component
public class RabbitAgentWorkConsumer {
    private final ResumeAnalysisWorker resumeAnalysisWorker;
    private final KnowledgeBaseIndexWorker knowledgeBaseIndexWorker;
    private final String defaultTargetRole;

    public RabbitAgentWorkConsumer(
            ResumeAnalysisWorker resumeAnalysisWorker,
            KnowledgeBaseIndexWorker knowledgeBaseIndexWorker,
            @Value("${agent.default-target-role:Java 后端}") String defaultTargetRole) {
        this.resumeAnalysisWorker = resumeAnalysisWorker;
        this.knowledgeBaseIndexWorker = knowledgeBaseIndexWorker;
        this.defaultTargetRole = defaultTargetRole;
    }

    @RabbitListener(queues = "${agent.rabbit.agent-work-queue:interview.agent.work.execute}")
    public void consume(AgentWorkTaskMessage message) {
        if (message == null || message.taskType() == null || message.resourceId() == null || message.userId() == null) {
            throw new BusinessException("AGENT_WORK_MESSAGE_INVALID", "异步任务消息不完整");
        }
        switch (message.taskType()) {
            case AgentWorkTaskMessage.RESUME_ANALYSIS ->
                    resumeAnalysisWorker.process(Long.parseLong(message.resourceId()), message.userId(), defaultTargetRole);
            case AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX ->
                    knowledgeBaseIndexWorker.process(message.resourceId(), message.userId());
            default -> throw new BusinessException("AGENT_WORK_TYPE_UNSUPPORTED", "不支持的异步任务类型");
        }
    }
}
