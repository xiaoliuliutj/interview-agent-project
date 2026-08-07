package com.interview.agent.upper.service;

/**
 * 只传递可重建的资源标识，避免将简历原文或 JPA 实体放进消息队列。
 */
public record AgentWorkTaskMessage(String taskType, String resourceId, String userId) {
    public static final String RESUME_ANALYSIS = "RESUME_ANALYSIS";
    public static final String KNOWLEDGE_BASE_INDEX = "KNOWLEDGE_BASE_INDEX";
}
