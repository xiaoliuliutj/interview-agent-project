package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {
    @Id
    private String id;
    private String name;
    private String category;
    private String originalFilename;
    private long fileSize;
    private String contentType;
    @Column(columnDefinition = "TEXT")
    private String content;
    private String vectorStatus;
    private String vectorError;
    private int chunkCount;
    private long accessCount;
    private long questionCount;
    private Instant createdAt;
    private Instant updatedAt;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(
            String id, String name, String category, String originalFilename,
            long fileSize, String contentType, String content) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.content = content;
        this.vectorStatus = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markVectorized(int chunkCount) {
        this.vectorStatus = "COMPLETED";
        this.vectorError = null;
        this.chunkCount = chunkCount;
        this.updatedAt = Instant.now();
    }

    public void markVectorFailed(String error) {
        this.vectorStatus = "FAILED";
        this.vectorError = error;
        this.updatedAt = Instant.now();
    }

    public void updateCategory(String category) { this.category = category; this.updatedAt = Instant.now(); }
    public void incrementQuestionCount() { this.questionCount++; this.accessCount++; this.updatedAt = Instant.now(); }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getOriginalFilename() { return originalFilename; }
    public long getFileSize() { return fileSize; }
    public String getContentType() { return contentType; }
    public String getContent() { return content; }
    public String getVectorStatus() { return vectorStatus; }
    public String getVectorError() { return vectorError; }
    public int getChunkCount() { return chunkCount; }
    public long getAccessCount() { return accessCount; }
    public long getQuestionCount() { return questionCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
