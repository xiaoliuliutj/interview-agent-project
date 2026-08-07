package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "candidates")
public class CandidateEntity {
    @Id
    private String id;
    private String userId;
    private String displayName;

    protected CandidateEntity() {
    }

    public CandidateEntity(String id, String userId, String displayName) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
}
