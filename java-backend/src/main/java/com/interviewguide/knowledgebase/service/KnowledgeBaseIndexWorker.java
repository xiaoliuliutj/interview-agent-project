package com.interviewguide.knowledgebase.service;

import com.interviewguide.common.exception.BusinessException;

import com.interviewguide.pythonagent.exception.PythonAgentException;
import com.interviewguide.pythonagent.mapper.PythonAgentClient;
import com.interviewguide.pythonagent.dto.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.dto.AgentRagIndexRequest;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.infrastructure.messaging.AgentWorkTaskMessage;
import com.interviewguide.infrastructure.messaging.RabbitTaskConfiguration;
import com.interviewguide.knowledgebase.domain.KnowledgeBaseEntity;
import com.interviewguide.knowledgebase.mapper.KnowledgeBaseRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseIndexWorker {
    private final PythonAgentClient pythonAgentClient;
    private final KnowledgeBasePersistenceService persistence;
    private final KnowledgeBaseRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public KnowledgeBaseIndexWorker(
            PythonAgentClient pythonAgentClient,
            KnowledgeBasePersistenceService persistence,
            KnowledgeBaseRepository repository,
            RabbitTemplate rabbitTemplate) {
        this.pythonAgentClient = pythonAgentClient;
        this.persistence = persistence;
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void index(String knowledgeBaseId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX, knowledgeBaseId, userId));
    }

    public void process(String knowledgeBaseId, String userId) {
        KnowledgeBaseEntity knowledgeBase = repository.findById(knowledgeBaseId).orElse(null);
        // A queue message can arrive after its source document was deleted.
        if (knowledgeBase == null) {
            return;
        }
        if (!userId.equals(knowledgeBase.getOwnerId())) {
            throw new BusinessException("KNOWLEDGE_BASE_ACCESS_DENIED", "knowledge base does not belong to current user");
        }
        /*
        // 闁告帞濞€濞呭骸霉娴ｈ　鏌ょ€规瓕灏欑划鈥愁嚕閳ь剚鎱ㄧ€ｎ偅顦ч柨娑樼焸濡诧箓宕氬鎹愬幀闁汇劌瀚Λ顐ゆ閵忕姷绌垮ù鐘侯嚙婵喐绋夊鍛箒闂佹彃绉甸弻濠囧礃濞嗗繐寮抽柛姘灴閸ｆ椽濡?        if (knowledgeBase.hasDeletionRequest()) {
        */
        if (knowledgeBase.hasDeletionRequest()) {
            return;
        }
        if (!persistence.markIndexing(knowledgeBase.getId())) {
            return;
        }
        try {
            AgentResponse response = pythonAgentClient.indexRag(new AgentRagIndexRequest(
                    "v1", UUID.randomUUID().toString(), "rag-index-" + knowledgeBase.getId(),
                    userId, "kb-" + knowledgeBase.getId(), "rag.index",
                    knowledgeBase.getContent(), List.of(knowledgeBase.getId()),
                    knowledgeBase.getId(), knowledgeBase.getOriginalFilename(), Instant.now()));
            if (response == null || response.code() < 100 || response.code() >= 200) {
                String message = response != null && response.error() != null
                        ? response.error().message() : "lower RAG indexing failed";
                persistence.markIndexFailed(knowledgeBase.getId(), message);
                if (response != null && response.retryable()) {
                    throw new PythonAgentException(message, null, true);
                }
                return;
            }
            KnowledgeBaseEntity latest = repository.findById(knowledgeBase.getId()).orElse(null);
            if (latest == null || latest.hasDeletionRequest()) {
                /*
                // 闁告帞濞€濞呭酣宕烽妸銉с偟闁稿繈鍎插﹢锟犳⒒閺夋垹纾诲┑顔碱儐閸ㄣ劎鈧懓鏈崹姘舵晬濮橆厾顏搁柣鐐叉濠€鏉库枎闄囩换婊堝礆閺夊灝鏅搁柛蹇嬪劤濞堟垿宕ラ幋锕€娅ら柨娑樺缁楁牜绱掑┑鍛憹闁搞儳鍋涢崯鎾舵閵忕姷绌块柟瀛樺姇婵盯鎮╅懜纰樺亾娴ｇ鍋?                AgentResponse deletion = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
                        "v1", UUID.randomUUID().toString(), "rag-delete-" + knowledgeBase.getId(),
                        userId, "kb-delete-" + knowledgeBase.getId(), "rag.delete", knowledgeBase.getId(), Instant.now()));
                */
                AgentResponse deletion = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
                        "v1", UUID.randomUUID().toString(), "rag-delete-" + knowledgeBase.getId(),
                        userId, "kb-delete-" + knowledgeBase.getId(), "rag.delete", knowledgeBase.getId(), Instant.now()));
                if (deletion == null || deletion.code() < 100 || deletion.code() >= 200) {
                    throw new BusinessException("KNOWLEDGE_BASE_VECTOR_DELETE_FAILED",
                            "late vector cleanup failed after knowledge-base deletion");
                }
                return;
            }
            persistence.markIndexed(latest.getId(), Integer.parseInt(response.answer()));
        } catch (RuntimeException error) {
            KnowledgeBaseEntity latest = repository.findById(knowledgeBaseId).orElse(null);
            /*
            // 闁告帞濞€濞呭酣鎮╅懜纰樺亾娴ｇ娑ч柤宕囨櫕閺侀亶宕氶悩缁樼彑婵炵繝鑳堕埢濂稿箳閵娿劎绠婚柨娑樼灱閸屻劌顕ｉ弴鐐杭閻犳劑鍎扮粭澶婎嚗濡や礁�?DELETING 閻熸洖妫涘ú濠囧箣?FAILED�?            if (latest != null && !latest.hasDeletionRequest()) {
            */
            if (latest != null && !latest.hasDeletionRequest()) {
                persistence.markIndexFailed(latest.getId(), error.getMessage());
            }
            // Only temporary lower-service failures are allowed to reach the
            // Rabbit listener retry policy.  Validation, contract and business
            // errors have already been persisted as FAILED and must be acked.
            if (error instanceof BusinessException
                    || error instanceof PythonAgentException gatewayError && !gatewayError.retryable()) {
                return;
            }
            throw error;
        }
    }
}
