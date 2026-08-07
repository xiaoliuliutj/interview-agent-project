package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.ScheduleParseRequest;
import com.interview.agent.upper.api.dto.ScheduleRequest;
import com.interview.agent.upper.api.dto.ScheduleView;
import com.interview.agent.upper.domain.InterviewScheduleEntity;
import com.interview.agent.upper.domain.ScheduleStatus;
import com.interview.agent.upper.repository.InterviewScheduleRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class InterviewScheduleService {
    private final InterviewScheduleRepository repository;

    public InterviewScheduleService(InterviewScheduleRepository repository) {
        this.repository = repository;
    }

    public ScheduleView parse(ScheduleParseRequest request) {
        String title = request.rawText() == null || request.rawText().isBlank()
                ? "未命名面试"
                : request.rawText().strip().lines().findFirst().orElse("未命名面试");
        return new ScheduleView(null, null, title, null, null, request.source(),
                ScheduleStatus.SCHEDULED.name(), request.rawText());
    }

    @Transactional
    public ScheduleView create(ScheduleRequest request) {
        return toView(repository.save(new InterviewScheduleEntity(
                request.userId(), request.title(), request.startAt(), request.endAt(),
                request.source(), ScheduleStatus.SCHEDULED, request.rawText())));
    }

    public ScheduleView get(Long id) { return toView(required(id)); }

    public List<ScheduleView> list(String userId, ScheduleStatus status, Instant start, Instant end) {
        List<InterviewScheduleEntity> items;
        if (start != null && end != null) {
            items = repository.findByUserIdAndStartAtBetweenOrderByStartAt(userId, start, end);
        } else if (status != null) {
            items = repository.findByUserIdAndStatusOrderByStartAt(userId, status);
        } else {
            items = repository.findAll();
        }
        return items.stream().filter(item -> userId == null || userId.equals(item.getUserId()))
                .map(this::toView).toList();
    }

    @Transactional
    public ScheduleView update(Long id, ScheduleRequest request) {
        InterviewScheduleEntity entity = required(id);
        entity.update(request.title(), request.startAt(), request.endAt(), request.source(), request.rawText());
        return toView(repository.save(entity));
    }

    @Transactional
    public ScheduleView updateStatus(Long id, ScheduleStatus status) {
        InterviewScheduleEntity entity = required(id);
        entity.updateStatus(status);
        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) { repository.delete(required(id)); }

    private InterviewScheduleEntity required(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("SCHEDULE_NOT_FOUND", "面试排期不存在"));
    }

    private ScheduleView toView(InterviewScheduleEntity entity) {
        return new ScheduleView(entity.getId(), entity.getUserId(), entity.getTitle(),
                entity.getStartAt(), entity.getEndAt(), entity.getSource(),
                entity.getStatus().name(), entity.getRawText());
    }
}
