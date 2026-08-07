package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "rag_chat_messages")
public class RagChatMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sessionId;
    @Column(length = 16)
    private String type;
    @Column(columnDefinition = "TEXT")
    private String content;
    private Instant createdAt;

    protected RagChatMessageEntity() {
    }

    public RagChatMessageEntity(Long sessionId, String type, String content) {
        this.sessionId = sessionId;
        this.type = type;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
