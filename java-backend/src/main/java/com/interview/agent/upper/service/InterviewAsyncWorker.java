package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.api.dto.InterviewView;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class InterviewAsyncWorker {
    private final InterviewService interviewService;
    private final InterviewTaskPersistenceService taskPersistence;

    public InterviewAsyncWorker(
            InterviewService interviewService,
            InterviewTaskPersistenceService taskPersistence) {
        this.interviewService = interviewService;
        this.taskPersistence = taskPersistence;
    }

    @Async("interviewTaskExecutor")
    public CompletableFuture<Void> createInterview(String taskId, CreateInterviewRequest request) {
        taskPersistence.markRunning(taskId);
        try {
            InterviewView result = interviewService.start(request);
            taskPersistence.markCompleted(taskId, result.sessionId());
        } catch (RuntimeException error) {
            taskPersistence.markFailed(taskId, safeMessage(error));
        }
        return CompletableFuture.completedFuture(null);
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
}
