package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.InterviewView;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RabbitInterviewTaskConsumer {
    private final InterviewService interviewService;
    private final InterviewTaskPersistenceService taskPersistence;

    public RabbitInterviewTaskConsumer(InterviewService interviewService,
                                       InterviewTaskPersistenceService taskPersistence) {
        this.interviewService = interviewService;
        this.taskPersistence = taskPersistence;
    }

    @RabbitListener(queues = "interview.agent.interview.create")
    public void consume(InterviewTaskMessage message) {
        if (taskPersistence.isCompleted(message.taskId())) {
            return;
        }
        taskPersistence.markRunning(message.taskId());
        try {
            InterviewView result = interviewService.start(message.request());
            taskPersistence.markCompleted(message.taskId(), result.sessionId());
        } catch (RuntimeException error) {
            taskPersistence.markFailed(message.taskId(), safeMessage(error));
            throw error; // 让 RabbitMQ 根据重试/死信策略重新投递
        }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
