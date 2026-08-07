package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "rag_chat_sessions")
public class RagChatSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    @Column(length = 120)
    private String title;
    @Column(length = 4000)
    private String knowledgeBaseIds;
    private boolean pinned;
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected RagChatSessionEntity() {
    }

    public RagChatSessionEntity(String userId, String title, List<Long> ids) {
        this.userId = userId;
        this.title = title == null || title.isBlank() ? "新对话" : title;
        this.knowledgeBaseIds = join(ids);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateTitle(String title) { this.title = title; this.updatedAt = Instant.now(); }
    public void updateKnowledgeBases(List<Long> ids) { this.knowledgeBaseIds = join(ids); this.updatedAt = Instant.now(); }
    public void togglePin() { this.pinned = !this.pinned; this.updatedAt = Instant.now(); }
    public void touch() { this.updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public List<Long> getKnowledgeBaseIdList() {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isBlank()) return List.of();
        return Arrays.stream(knowledgeBaseIds.split(",")).map(Long::parseLong).toList();
    }
    public boolean isPinned() { return pinned; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static String join(List<Long> ids) {
        return ids == null ? "" : ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
}
