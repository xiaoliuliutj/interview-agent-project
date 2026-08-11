package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {
    @Id
    private String id;
    // 旧库升级阶段允许 NULL；新写入由 KnowledgeBaseService 强制提供用户归属。
    @Column
    private String ownerId;
    private String name;
    private String category;
    private String originalFilename;
    private long fileSize;
    private String contentType;
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(columnDefinition = "BYTEA")
    private byte[] originalBytes;
    @Column(columnDefinition = "TEXT")
    private String content;
    private String vectorStatus;
    private String vectorError;
    private int chunkCount;
    private Instant createdAt;
    private Instant updatedAt;
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;
    @Column(name = "source_title", length = 500)
    private String sourceTitle;
    @Column(name = "source_fetched_at")
    private Instant sourceFetchedAt;
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(
            String id, String ownerId, String name, String category, String originalFilename,
            long fileSize, String contentType, String content) {
        this.id = id;
        this.ownerId = ownerId;
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

    public void markVectorPending() {
        this.vectorStatus = "PENDING";
        this.vectorError = null;
        this.updatedAt = Instant.now();
    }

    public boolean markVectorProcessing() {
        if (hasDeletionRequest()) return false;
        this.vectorStatus = "PROCESSING";
        this.vectorError = null;
        this.updatedAt = Instant.now();
        return true;
    }

    public void markVectorFailed(String error) {
        this.vectorStatus = "FAILED";
        this.vectorError = error;
        this.updatedAt = Instant.now();
    }

    public void markDeleting() {
        this.vectorStatus = "DELETING";
        this.vectorError = null;
        this.updatedAt = Instant.now();
    }

    public void markDeleteFailed(String error) {
        this.vectorStatus = "DELETE_FAILED";
        this.vectorError = error == null ? "vector cleanup failed"
                : error.substring(0, Math.min(500, error.length()));
        this.updatedAt = Instant.now();
    }

    public boolean isDeleting() { return "DELETING".equals(vectorStatus); }

    /** 删除失败并不代表资料可以重新索引；只有重新执行删除才能推进该状态。 */
    public boolean hasDeletionRequest() {
        return "DELETING".equals(vectorStatus) || "DELETE_FAILED".equals(vectorStatus);
    }

    public void updateCategory(String category) { this.category = category; this.updatedAt = Instant.now(); }
    public void attachOriginalBytes(byte[] bytes) { this.originalBytes = bytes; }
    public void attachWebSource(String url, String title, Instant fetchedAt, String hash) {
        this.sourceUrl = url;
        this.sourceTitle = title;
        this.sourceFetchedAt = fetchedAt;
        this.sourceHash = hash;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getOriginalFilename() { return originalFilename; }
    public long getFileSize() { return fileSize; }
    public String getContentType() { return contentType; }
    public byte[] getOriginalBytes() { return originalBytes; }
    public String getContent() { return content; }
    public String getVectorStatus() { return vectorStatus; }
    public String getVectorError() { return vectorError; }
    public int getChunkCount() { return chunkCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public Instant getSourceFetchedAt() { return sourceFetchedAt; }
    public String getSourceHash() { return sourceHash; }
}
