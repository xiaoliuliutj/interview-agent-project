package com.interview.agent.upper.service;

import com.interview.agent.upper.domain.InterviewTaskEntity;
import com.interview.agent.upper.repository.InterviewTaskRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InterviewTaskPersistenceService {
    private final InterviewTaskRepository repository;

    public InterviewTaskPersistenceService(InterviewTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public InterviewTaskEntity create(String taskType) {
        return repository.save(new InterviewTaskEntity(UUID.randomUUID().toString(), taskType));
    }

    @Transactional
    public void markRunning(String taskId) {
        required(taskId).running();
    }

    @Transactional
    public void markCompleted(String taskId, String sessionId) {
        required(taskId).complete(sessionId);
    }

    @Transactional
    public void markFailed(String taskId, String message) {
        required(taskId).fail(message);
    }

    public InterviewTaskEntity get(String taskId) {
        return required(taskId);
    }

    private InterviewTaskEntity required(String taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "异步任务不存在"));
    }
}
