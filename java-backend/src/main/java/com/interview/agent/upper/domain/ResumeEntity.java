package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "resumes")
public class ResumeEntity {
    @Id
    private String id;
    private String candidateId;
    private int version;
    @Column(columnDefinition = "TEXT")
    private String content;

    protected ResumeEntity() {
    }

    public ResumeEntity(String id, String candidateId, int version, String content) {
        this.id = id;
        this.candidateId = candidateId;
        this.version = version;
        this.content = content;
    }

    public String getId() { return id; }
    public String getCandidateId() { return candidateId; }
    public int getVersion() { return version; }
    public String getContent() { return content; }
}
