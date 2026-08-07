package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.ScheduleParseRequest;
import com.interview.agent.upper.api.dto.ScheduleRequest;
import com.interview.agent.upper.api.dto.ScheduleView;
import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentScheduleParseRequest;
import com.interview.agent.upper.domain.InterviewScheduleEntity;
import com.interview.agent.upper.domain.ScheduleStatus;
import com.interview.agent.upper.repository.InterviewScheduleRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InterviewScheduleService {
    private final InterviewScheduleRepository repository;
    private final UserIdentityResolver identity;
    private final AgentGateway agentGateway;

    public InterviewScheduleService(
            InterviewScheduleRepository repository, UserIdentityResolver identity, AgentGateway agentGateway) {
        this.repository = repository;
        this.identity = identity;
        this.agentGateway = agentGateway;
    }

    public ScheduleView parse(ScheduleParseRequest request, String userId) {
        String owner = identity.require(userId);
        if (request.rawText() == null || request.rawText().isBlank()) {
            throw new BusinessException("SCHEDULE_RAW_TEXT_REQUIRED", "日程文本不能为空");
        }
        String sessionId = "schedule-" + UUID.randomUUID();
        AgentResponse response = agentGateway.parseSchedule(new AgentScheduleParseRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner,
                sessionId, "agent.schedule.parse", request.rawText(), ZoneId.systemDefault().getId()));
        if (response.code() < 100 || response.code() >= 200 || response.output() == null) {
            String message = response.error() == null ? "下层日程解析失败" : response.error().message();
            throw new BusinessException("SCHEDULE_PARSE_FAILED", message);
        }
        Map<String, Object> output = response.output();
        String title = requiredText(output, "title");
        Instant startAt = instant(output.get("startAt"));
        Instant endAt = instant(output.get("endAt"));
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            throw new BusinessException("SCHEDULE_TIME_RANGE_INVALID", "结束时间必须晚于开始时间");
        }
        return new ScheduleView(null, owner, title, startAt, endAt, request.source(),
                ScheduleStatus.SCHEDULED.name(), request.rawText());
    }

    @Transactional
    public ScheduleView create(ScheduleRequest request, String userId) {
        String owner = identity.require(userId);
        if (!owner.equals(request.userId())) throw new BusinessException("USER_ID_MISMATCH", "schedule userId does not match X-User-Id");
        return toView(repository.save(new InterviewScheduleEntity(
                request.userId(), request.title(), request.startAt(), request.endAt(),
                request.source(), ScheduleStatus.SCHEDULED, request.rawText())));
    }

    public ScheduleView get(Long id, String userId) { return toView(owned(id, userId)); }

    public List<ScheduleView> list(String userId, ScheduleStatus status, Instant start, Instant end) {
        String owner = identity.require(userId);
        List<InterviewScheduleEntity> items;
        if (start != null && end != null) {
            items = repository.findByUserIdAndStartAtBetweenOrderByStartAt(owner, start, end);
        } else if (status != null) {
            items = repository.findByUserIdAndStatusOrderByStartAt(owner, status);
        } else {
            items = repository.findByUserIdOrderByStartAt(owner);
        }
        return items.stream().filter(item -> owner.equals(item.getUserId()))
                .map(this::toView).toList();
    }

    @Transactional
    public ScheduleView update(Long id, ScheduleRequest request, String userId) {
        InterviewScheduleEntity entity = owned(id, userId);
        entity.update(request.title(), request.startAt(), request.endAt(), request.source(), request.rawText());
        return toView(repository.save(entity));
    }

    @Transactional
    public ScheduleView updateStatus(Long id, ScheduleStatus status, String userId) {
        InterviewScheduleEntity entity = owned(id, userId);
        entity.updateStatus(status);
        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(Long id, String userId) { repository.delete(owned(id, userId)); }

    private InterviewScheduleEntity owned(Long id, String userId) {
        InterviewScheduleEntity entity = required(id);
        if (!identity.require(userId).equals(entity.getUserId())) {
            throw new BusinessException("SCHEDULE_ACCESS_DENIED", "schedule does not belong to current user");
        }
        return entity;
    }

    private InterviewScheduleEntity required(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("SCHEDULE_NOT_FOUND", "面试排期不存在"));
    }

    private ScheduleView toView(InterviewScheduleEntity entity) {
        return new ScheduleView(entity.getId(), entity.getUserId(), entity.getTitle(),
                entity.getStartAt(), entity.getEndAt(), entity.getSource(),
                entity.getStatus().name(), entity.getRawText());
    }

    private String requiredText(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BusinessException("SCHEDULE_PARSE_OUTPUT_INVALID", "下层未返回有效标题");
        }
        return text.strip();
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BusinessException("SCHEDULE_PARSE_OUTPUT_INVALID", "下层返回的时间格式无效");
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException error) {
            throw new BusinessException("SCHEDULE_PARSE_OUTPUT_INVALID", "下层返回的时间不是 ISO-8601 格式");
        }
    }
}
