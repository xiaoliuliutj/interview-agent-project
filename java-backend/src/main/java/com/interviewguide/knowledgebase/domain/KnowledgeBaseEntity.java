package com.interviewguide.knowledgebase.domain;

import com.interviewguide.knowledgebase.service.KnowledgeBaseService;


import java.time.Instant;

public class KnowledgeBaseEntity {
    private String id;
    // 鏃у簱鍗囩骇闃舵鍏佽 NULL锛涙柊鍐欏叆鐢?KnowledgeBaseService 寮哄埗鎻愪緵鐢ㄦ埛褰掑睘銆?    @Column
    private String ownerId;
    private String name;
    private String category;
    private String originalFilename;
    private long fileSize;
    private String contentType;
    private byte[] originalBytes;
    private String content;
    private String vectorStatus;
    private String vectorError;
    private int chunkCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String sourceUrl;
    private String sourceTitle;
    private Instant sourceFetchedAt;
    private String sourceHash;

    public KnowledgeBaseEntity() {
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

    /** 鍒犻櫎澶辫触骞朵笉浠ｈ〃璧勬枡鍙互閲嶆柊绱㈠紩锛涘彧鏈夐噸鏂版墽琛屽垹闄ゆ墠鑳芥帹杩涜鐘舵€併€?*/
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
