package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.api.dto.InterviewView;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.interview.agent.upper.config.RabbitTaskConfiguration;

import java.util.concurrent.CompletableFuture;

@Service
public class InterviewAsyncWorker {
    private final InterviewService interviewService;
    private final InterviewTaskPersistenceService taskPersistence;
    private final RabbitTemplate rabbitTemplate;

    public InterviewAsyncWorker(
            InterviewService interviewService,
            InterviewTaskPersistenceService taskPersistence,
            RabbitTemplate rabbitTemplate) {
        this.interviewService = interviewService;
        this.taskPersistence = taskPersistence;
        this.rabbitTemplate = rabbitTemplate;
    }

    public CompletableFuture<Void> createInterview(String taskId, CreateInterviewRequest request) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.ROUTING_KEY, new InterviewTaskMessage(taskId, request));
        return CompletableFuture.completedFuture(null);
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
}
