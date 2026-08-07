package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "interview_schedules")
public class InterviewScheduleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String title;
    private Instant startAt;
    private Instant endAt;
    private String source;
    @Enumerated(EnumType.STRING)
    private ScheduleStatus status;
    @Column(columnDefinition = "TEXT")
    private String rawText;
    private Instant createdAt;

    protected InterviewScheduleEntity() {
    }

    public InterviewScheduleEntity(
            String userId,
            String title,
            Instant startAt,
            Instant endAt,
            String source,
            ScheduleStatus status,
            String rawText) {
        this.userId = userId;
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.source = source;
        this.status = status;
        this.rawText = rawText;
        this.createdAt = Instant.now();
    }

    public void update(String title, Instant startAt, Instant endAt, String source, String rawText) {
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.source = source;
        this.rawText = rawText;
    }

    public void updateStatus(ScheduleStatus status) { this.status = status; }
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public String getSource() { return source; }
    public ScheduleStatus getStatus() { return status; }
    public String getRawText() { return rawText; }
    public Instant getCreatedAt() { return createdAt; }
}
