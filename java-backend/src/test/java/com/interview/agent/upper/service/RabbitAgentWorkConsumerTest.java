package com.interview.agent.upper.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RabbitAgentWorkConsumerTest {

    @Test
    void invalidMessageIsAcknowledgedWithoutInvokingAnyWorker() {
        ResumeAnalysisWorker resumeWorker = mock(ResumeAnalysisWorker.class);
        KnowledgeBaseIndexWorker knowledgeBaseWorker = mock(KnowledgeBaseIndexWorker.class);
        RabbitAgentWorkConsumer consumer = new RabbitAgentWorkConsumer(resumeWorker, knowledgeBaseWorker);

        consumer.consume(null);

        verifyNoInteractions(resumeWorker, knowledgeBaseWorker);
    }
}
