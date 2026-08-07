package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.KnowledgeBaseQueryRequest;
import com.interview.agent.upper.api.dto.KnowledgeBaseQueryResponse;
import com.interview.agent.upper.api.dto.KnowledgeBaseView;
import com.interview.agent.upper.api.dto.RagChatMessageView;
import com.interview.agent.upper.api.dto.RagChatSessionDetailView;
import com.interview.agent.upper.api.dto.RagChatSessionListItemView;
import com.interview.agent.upper.api.dto.RagChatSessionView;
import com.interview.agent.upper.domain.RagChatMessageEntity;
import com.interview.agent.upper.domain.RagChatSessionEntity;
import com.interview.agent.upper.repository.RagChatMessageRepository;
import com.interview.agent.upper.repository.RagChatSessionRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Java 上层的知识库问答业务会话。
 *
 * <p>它只管理前端可见的会话和消息，不实现切片、向量检索或模型调用；这些能力仍通过
 * {@link KnowledgeBaseService} 委托给 Python 下层。网络调用不放进数据库事务，避免慢检索长期占用事务连接。</p>
 */
@Service
public class RagChatService {
    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final String demoUserId;

    public RagChatService(
            RagChatSessionRepository sessionRepository,
            RagChatMessageRepository messageRepository,
            KnowledgeBaseService knowledgeBaseService,
            @Value("${agent.demo-user-id}") String demoUserId) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.demoUserId = demoUserId;
    }

    @Transactional
    public RagChatSessionView create(List<Long> knowledgeBaseIds, String title) {
        List<Long> ids = normalizeKnowledgeBaseIds(knowledgeBaseIds);
        RagChatSessionEntity session = sessionRepository.save(
                new RagChatSessionEntity(demoUserId, title, ids));
        return toSessionView(session);
    }

    public List<RagChatSessionListItemView> list() {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(demoUserId).stream()
                .sorted(Comparator.<RagChatSessionEntity, Boolean>comparing(RagChatSessionEntity::isPinned)
                        .reversed()
                        .thenComparing(RagChatSessionEntity::getUpdatedAt, Comparator.reverseOrder()))
                .map(this::toListItem)
                .toList();
    }

    public RagChatSessionDetailView detail(Long sessionId) {
        RagChatSessionEntity session = required(sessionId);
        List<KnowledgeBaseView> knowledgeBases = session.getKnowledgeBaseIdList().stream()
                .map(this::findKnowledgeBaseOrNull)
                .filter(item -> item != null)
                .toList();
        List<RagChatMessageView> messages = messageRepository.findBySessionIdOrderByCreatedAt(sessionId)
                .stream().map(this::toMessageView).toList();
        return new RagChatSessionDetailView(
                session.getId(), session.getTitle(), knowledgeBases, messages,
                session.getCreatedAt(), session.getUpdatedAt());
    }

    @Transactional
    public void updateTitle(Long sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException("RAG_CHAT_TITLE_REQUIRED", "会话标题不能为空");
        }
        RagChatSessionEntity session = required(sessionId);
        session.updateTitle(title.strip());
    }

    @Transactional
    public void updateKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        required(sessionId).updateKnowledgeBases(normalizeKnowledgeBaseIds(knowledgeBaseIds));
    }

    @Transactional
    public void togglePin(Long sessionId) {
        required(sessionId).togglePin();
    }

    @Transactional
    public void delete(Long sessionId) {
        RagChatSessionEntity session = required(sessionId);
        messageRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    /**
     * 保证用户消息先落库，随后才调用 Python RAG。下层失败时保留问题供用户重试，
     * 但不伪造一条 assistant 回复。
     */
    public String answer(Long sessionId, String question) {
        RagChatSessionEntity session = required(sessionId);
        String normalizedQuestion = question == null ? "" : question.strip();
        if (normalizedQuestion.isEmpty()) {
            throw new BusinessException("RAG_CHAT_QUESTION_REQUIRED", "问题不能为空");
        }
        List<Long> knowledgeBaseIds = session.getKnowledgeBaseIdList();
        normalizeKnowledgeBaseIds(knowledgeBaseIds);
        messageRepository.save(new RagChatMessageEntity(sessionId, "user", normalizedQuestion));

        // 外部服务调用不包在 @Transactional 中，避免占用连接或把失败的外部调用回滚成重复问答。
        KnowledgeBaseQueryResponse ragResponse = knowledgeBaseService.query(
                new KnowledgeBaseQueryRequest(knowledgeBaseIds, normalizedQuestion));
        String answer = ragResponse.answer();

        messageRepository.save(new RagChatMessageEntity(sessionId, "assistant", answer));
        session.touch();
        sessionRepository.save(session);
        return answer;
    }

    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException("KNOWLEDGE_BASE_IDS_REQUIRED", "请选择至少一个知识库");
        }
        List<Long> ids = knowledgeBaseIds.stream().distinct().toList();
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException("KNOWLEDGE_BASE_ID_INVALID", "知识库编号不合法");
        }
        ids.forEach(id -> knowledgeBaseService.get(id));
        return ids;
    }

    private RagChatSessionEntity required(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .filter(session -> demoUserId.equals(session.getUserId()))
                .orElseThrow(() -> new BusinessException("RAG_CHAT_SESSION_NOT_FOUND", "知识库问答会话不存在"));
    }

    private KnowledgeBaseView findKnowledgeBaseOrNull(long knowledgeBaseId) {
        try {
            return knowledgeBaseService.get(knowledgeBaseId);
        } catch (BusinessException ignored) {
            // 已删除的知识库不能阻断历史聊天记录展示。
            return null;
        }
    }

    private RagChatSessionView toSessionView(RagChatSessionEntity entity) {
        return new RagChatSessionView(entity.getId(), entity.getTitle(),
                entity.getKnowledgeBaseIdList(), entity.getCreatedAt());
    }

    private RagChatSessionListItemView toListItem(RagChatSessionEntity entity) {
        List<String> names = entity.getKnowledgeBaseIdList().stream()
                .map(this::findKnowledgeBaseOrNull)
                .filter(item -> item != null)
                .map(KnowledgeBaseView::name)
                .toList();
        return new RagChatSessionListItemView(
                entity.getId(), entity.getTitle(), messageRepository.countBySessionId(entity.getId()),
                names, entity.getUpdatedAt(), entity.isPinned());
    }

    private RagChatMessageView toMessageView(RagChatMessageEntity entity) {
        return new RagChatMessageView(entity.getId(), entity.getType(), entity.getContent(), entity.getCreatedAt());
    }
}
