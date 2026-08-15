package com.interviewguide.infrastructure.messaging;

/**
 * 鍙紶閫掑彲閲嶅缓鐨勮祫婧愭爣璇嗭紝閬垮厤灏嗙畝鍘嗗師鏂囨垨 JPA 瀹炰綋鏀捐繘娑堟伅闃熷垪銆? */
public record AgentWorkTaskMessage(String taskType, String resourceId, String userId) {
    public static final String RESUME_ANALYSIS = "RESUME_ANALYSIS";
    public static final String KNOWLEDGE_BASE_INDEX = "KNOWLEDGE_BASE_INDEX";
}
