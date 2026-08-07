package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "resumes")
public class ResumeEntity {
    @Id
    private String id;
    private String candidateId;
    private int version;
    @Column(columnDefinition = "TEXT")
    private String content;
    @Column(length = 64)
    private String fileHash;
    private String originalFilename;
    private long fileSize;
    private String contentType;
    private String storageKey;
    private Instant createdAt;

    protected ResumeEntity() {
    }

    public ResumeEntity(String id, String candidateId, int version, String content) {
        this.id = id;
        this.candidateId = candidateId;
        this.version = version;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getCandidateId() { return candidateId; }
    public int getVersion() { return version; }
    public String getContent() { return content; }
    public String getFileHash() { return fileHash; }
    public String getOriginalFilename() { return originalFilename; }
    public long getFileSize() { return fileSize; }
    public String getContentType() { return contentType; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }

    public void attachFile(String hash, String filename, long size, String type, String key) {
        this.fileHash = hash;
        this.originalFilename = filename;
        this.fileSize = size;
        this.contentType = type;
        this.storageKey = key;
    }
}
