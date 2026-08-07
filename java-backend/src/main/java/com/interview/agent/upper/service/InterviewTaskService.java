package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.domain.InterviewTaskEntity;
import org.springframework.stereotype.Service;

@Service
public class InterviewTaskService {
    private final InterviewTaskPersistenceService taskPersistence;
    private final InterviewAsyncWorker worker;

    public InterviewTaskService(
            InterviewTaskPersistenceService taskPersistence,
            InterviewAsyncWorker worker) {
        this.taskPersistence = taskPersistence;
        this.worker = worker;
    }

    public InterviewTaskEntity submitCreate(CreateInterviewRequest request) {
        InterviewTaskEntity task = taskPersistence.create("INTERVIEW_INITIALIZE");
        try {
            worker.createInterview(task.getId(), request);
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            taskPersistence.markFailed(task.getId(), message);
            throw error;
        }
        return task;
    }

    public InterviewTaskEntity get(String taskId) {
        return taskPersistence.get(taskId);
    }
}
