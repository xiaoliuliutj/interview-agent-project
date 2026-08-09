package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "interview_turns")
public class InterviewTurnEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionId;
    private String runId;
    @Column(columnDefinition = "TEXT")
    private String question;
    @Column(columnDefinition = "TEXT")
    private String candidateAnswer;
    private String stage;
    private Instant createdAt;

    protected InterviewTurnEntity() {
    }

    public InterviewTurnEntity(
            String sessionId,
            String runId,
            String question,
            String candidateAnswer) {
        this.sessionId = sessionId;
        this.runId = runId;
        this.question = question;
        this.candidateAnswer = candidateAnswer;
        this.createdAt = Instant.now();
    }

    public void setStage(String stage) { this.stage = stage; }
    public String getStage() { return stage; }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getRunId() { return runId; }
    public String getQuestion() { return question; }
    public String getCandidateAnswer() { return candidateAnswer; }
    public Instant getCreatedAt() { return createdAt; }
}
