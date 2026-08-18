package com.interviewguide.resume.domain;

/** Persistent entity that associates one application user with resume versions. */
public class CandidateEntity {
    private String id;
    private String userId;
    private String displayName;
    private String currentResumeId;

    public CandidateEntity() {
    }

    public CandidateEntity(String id, String userId, String displayName) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getCurrentResumeId() { return currentResumeId; }
    public void setCurrentResumeId(String resumeId) { this.currentResumeId = resumeId; }
}
