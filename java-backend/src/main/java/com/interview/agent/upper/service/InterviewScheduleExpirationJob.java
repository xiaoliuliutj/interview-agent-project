package com.interview.agent.upper.service;

import com.interview.agent.upper.domain.InterviewScheduleEntity;
import com.interview.agent.upper.domain.ScheduleStatus;
import com.interview.agent.upper.repository.InterviewScheduleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 将已经过结束时间且未取消的日程推进为已完成，避免日历状态永久停留。 */
@Component
public class InterviewScheduleExpirationJob {
    private final InterviewScheduleRepository repository;

    public InterviewScheduleExpirationJob(InterviewScheduleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${agent.schedule-expiration-interval-ms:60000}")
    public void completeExpiredSchedules() {
        List<InterviewScheduleEntity> expired = repository.findByStatusInAndEndAtBefore(
                List.of(ScheduleStatus.SCHEDULED, ScheduleStatus.CONFIRMED), Instant.now());
        expired.forEach(item -> item.updateStatus(ScheduleStatus.COMPLETED));
    }
}
