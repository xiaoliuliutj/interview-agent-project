package com.interviewguide.knowledgebase.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseEntityTest {

    @Test
    void deletionStateIsExplicitAndKeepsCleanupFailureVisible() {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity(
                "kb-1", "user-1", "Java 基础", "system", "java.md",
                100, "text/markdown", "缓存一致性资料");

        knowledgeBase.markDeleting();

        assertTrue(knowledgeBase.isDeleting());
        assertEquals("DELETING", knowledgeBase.getVectorStatus());
        assertNull(knowledgeBase.getVectorError());

        knowledgeBase.markDeleteFailed("vector cleanup timeout");

        assertFalse(knowledgeBase.isDeleting());
        assertTrue(knowledgeBase.hasDeletionRequest());
        assertEquals("DELETE_FAILED", knowledgeBase.getVectorStatus());
        assertEquals("vector cleanup timeout", knowledgeBase.getVectorError());
    }

    @Test
    void indexingStateTransitionsAreRealAndCannotOverrideDeletion() {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity(
                "kb-1", "user-1", "Java 基础", "system", "java.md",
                100, "text/markdown", "缓存一致性资料");

        assertEquals("PENDING", knowledgeBase.getVectorStatus());
        assertTrue(knowledgeBase.markVectorProcessing());
        assertEquals("PROCESSING", knowledgeBase.getVectorStatus());

        knowledgeBase.markVectorFailed("embedding timeout");
        knowledgeBase.markVectorPending();
        assertEquals("PENDING", knowledgeBase.getVectorStatus());
        assertNull(knowledgeBase.getVectorError());

        knowledgeBase.markDeleting();
        assertFalse(knowledgeBase.markVectorProcessing());
        assertEquals("DELETING", knowledgeBase.getVectorStatus());
    }
}
