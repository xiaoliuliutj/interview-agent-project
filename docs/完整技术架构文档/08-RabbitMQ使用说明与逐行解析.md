# RabbitMQ 使用说明：发布、消费、重试、死信与逐函数解析

## 1. 使用范围

当前项目只有 Java 后端直接连接 RabbitMQ；Python Agent 通过 HTTP 被 Java Worker 调用，不是 RabbitMQ 消费者。RabbitMQ 承载简历分析和知识库向量索引两类可重建异步任务。

| 角色 | 服务/类 | 任务 |
|---|---|---|
| 发布方 1 | `ResumeAnalysisWorker.enqueue` | `RESUME_ANALYSIS` |
| 发布方 2 | `KnowledgeBaseIndexWorker.index` | `KNOWLEDGE_BASE_INDEX` |
| 消费方 | `RabbitAgentWorkConsumer.consume` | 按 taskType 分发 |
| 实际处理方 | 两个 Worker.process | 调用 Python HTTP 并更新 PostgreSQL |
| Python | FastAPI Agent | 不连接 RabbitMQ |

## 2. 拓扑和消息格式

- 主交换机：`interview.agent.work`。
- 主队列：`interview.agent.work.execute`。
- routing key：`agent.work.execute`。
- 死信交换机/队列：`interview.agent.work.dlx` / `interview.agent.work.execute.dlq`。
- 消息：`AgentWorkTaskMessage(taskType, resourceId, userId)`，只携带可重建资源标识。

```text
Worker.enqueue/index
 -> RabbitTemplate.convertAndSend
 -> 主交换机 -> 主队列
 -> RabbitAgentWorkConsumer.consume
 -> ResumeAnalysisWorker.process / KnowledgeBaseIndexWorker.process
 -> Python HTTP Agent -> PostgreSQL
```

## 3. 当前源码代码块与函数解析

### 3.1 java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitTaskConfiguration.java

~~~java
package com.interviewguide.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** RabbitMQ topology for concrete Agent work only: resume analysis and knowledge-base indexing. */
@Configuration
public class RabbitTaskConfiguration {
    public static final String EXCHANGE = "interview.agent.work";
    public static final String AGENT_WORK_QUEUE = "interview.agent.work.execute";
    public static final String AGENT_WORK_ROUTING_KEY = "agent.work.execute";
    public static final String DEAD_LETTER_EXCHANGE = "interview.agent.work.dlx";
    public static final String AGENT_WORK_DEAD_LETTER_QUEUE = "interview.agent.work.execute.dlq";

    @Bean
    DirectExchange agentWorkExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean
    Queue agentWorkQueue() {
        return new Queue(AGENT_WORK_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", AGENT_WORK_ROUTING_KEY));
    }

    @Bean
    Binding agentWorkBinding(@Qualifier("agentWorkQueue") Queue queue,
                             @Qualifier("agentWorkExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    DirectExchange agentWorkDeadLetterExchange() { return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false); }

    @Bean
    Queue agentWorkDeadLetterQueue() { return new Queue(AGENT_WORK_DEAD_LETTER_QUEUE, true); }

    @Bean
    Binding agentWorkDeadLetterBinding(@Qualifier("agentWorkDeadLetterQueue") Queue queue,
                                       @Qualifier("agentWorkDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(AGENT_WORK_ROUTING_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
~~~

### 3.2 java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitAgentWorkConsumer.java

~~~java
package com.interviewguide.infrastructure.messaging;

import com.interviewguide.knowledgebase.service.KnowledgeBaseIndexWorker;
import com.interviewguide.resume.service.ResumeAnalysisWorker;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RabbitAgentWorkConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RabbitAgentWorkConsumer.class);
    private final ResumeAnalysisWorker resumeAnalysisWorker;
    private final KnowledgeBaseIndexWorker knowledgeBaseIndexWorker;

    public RabbitAgentWorkConsumer(ResumeAnalysisWorker resumeAnalysisWorker,
                                   KnowledgeBaseIndexWorker knowledgeBaseIndexWorker) {
        this.resumeAnalysisWorker = resumeAnalysisWorker;
        this.knowledgeBaseIndexWorker = knowledgeBaseIndexWorker;
    }

    @RabbitListener(queues = "${agent.rabbit.agent-work-queue:interview.agent.work.execute}")
    public void consume(AgentWorkTaskMessage message) {
        if (message == null || message.taskType() == null || message.resourceId() == null || message.userId() == null) {
            logger.error("Discarding invalid Agent work message: {}", message);
            return;
        }
        try {
            switch (message.taskType()) {
                case AgentWorkTaskMessage.RESUME_ANALYSIS ->
                        resumeAnalysisWorker.process(Long.parseLong(message.resourceId()), message.userId());
                case AgentWorkTaskMessage.KNOWLEDGE_BASE_INDEX ->
                        knowledgeBaseIndexWorker.process(message.resourceId(), message.userId());
                default -> logger.error("Discarding unsupported Agent work type: {}", message.taskType());
            }
        } catch (NumberFormatException error) {
            logger.error("Discarding Agent work message with invalid resource ID: {}", message.resourceId());
        }
    }
}
~~~

#### `consume`（第 23 行）

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitAgentWorkConsumer.java:23`

1. 第 23 行定义函数；校验消息后按 taskType 分发；无效任务只记录错误并返回，避免毒消息循环。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


### 3.3 java-backend/src/main/java/com/interviewguide/infrastructure/messaging/AgentWorkTaskMessage.java

~~~java
package com.interviewguide.infrastructure.messaging;

/**
 * 鍙紶閫掑彲閲嶅缓鐨勮祫婧愭爣璇嗭紝閬垮厤灏嗙畝鍘嗗師鏂囨垨 JPA 瀹炰綋鏀捐繘娑堟伅闃熷垪銆? */
public record AgentWorkTaskMessage(String taskType, String resourceId, String userId) {
    public static final String RESUME_ANALYSIS = "RESUME_ANALYSIS";
    public static final String KNOWLEDGE_BASE_INDEX = "KNOWLEDGE_BASE_INDEX";
}
~~~

#### `AgentWorkTaskMessage`（第 5 行）

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/messaging/AgentWorkTaskMessage.java:5`

1. 第 5 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


### 3.4 java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java

~~~java
package com.interviewguide.resume.service;

import com.interviewguide.pythonagent.exception.PythonAgentException;
import com.interviewguide.pythonagent.mapper.PythonAgentClient;
import com.interviewguide.pythonagent.dto.AgentResumeEvaluateRequest;
import com.interviewguide.pythonagent.dto.AgentResumeMemoryActivationRequest;
import com.interviewguide.pythonagent.dto.AgentResponse;
import com.interviewguide.resume.domain.CandidateEntity;
import com.interviewguide.resume.domain.ResumeEntity;
import com.interviewguide.resume.mapper.CandidateRepository;
import com.interviewguide.resume.mapper.ResumeAnalysisRepository;
import com.interviewguide.resume.mapper.ResumeRepository;
import com.interviewguide.infrastructure.messaging.RabbitTaskConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ResumeAnalysisWorker {
    private final PythonAgentClient pythonAgentClient;
    private final ResumeAnalysisPersistenceService persistence;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final RabbitTemplate rabbitTemplate;
    private final int maxDeliveryAttempts;

    public ResumeAnalysisWorker(
            PythonAgentClient pythonAgentClient,
            ResumeAnalysisPersistenceService persistence,
            ResumeAnalysisRepository analysisRepository,
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxDeliveryAttempts) {
        this.pythonAgentClient = pythonAgentClient;
        this.persistence = persistence;
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
    }

    public void enqueue(Long analysisId, String userId) {
        rabbitTemplate.convertAndSend(RabbitTaskConfiguration.EXCHANGE,
                RabbitTaskConfiguration.AGENT_WORK_ROUTING_KEY,
                new AgentWorkTaskMessage(AgentWorkTaskMessage.RESUME_ANALYSIS, analysisId.toString(), userId));
    }

    public void process(Long analysisId, String userId) {
        var analysis = analysisRepository.findById(analysisId).orElse(null);
        // The user can upload a replacement or delete a resume after the message was sent.
        // Such a message is obsolete, not a retryable infrastructure failure.
        if (analysis == null || analysis.isCancelled()) {
            return;
        }
        ResumeEntity resume = resumeRepository.findById(analysis.getResumeId()).orElse(null);
        if (resume == null) {
            return;
        }
        if (!isCurrentResume(resume)) {
            persistence.cancel(analysisId);
            return;
        }
        var attempt = persistence.beginAttempt(analysisId);
        if (attempt == null) {
            return;
        }
        try {
            String activationRunId = "resume-memory-" + analysisId;
            String activationSessionId = "resume-memory-" + resume.getCandidateId();
            AgentResponse activation = pythonAgentClient.activateResumeMemory(
                    new AgentResumeMemoryActivationRequest(
                            "v1", UUID.randomUUID().toString(), activationRunId,
                            userId, activationSessionId,
                            "agent.resume.activate", resume.getId(), resume.getCandidateId(),
                            resume.getContent(), analysis.getTargetRole(), Instant.now()));
            requireMatchingResponse(
                    activation, "lower resume memory activation failed",
                    userId, activationSessionId, activationRunId);
            if (!isCurrentResume(resume)) {
                persistence.cancel(analysisId);
                return;
            }
            String evaluationRunId = "resume-evaluation-" + analysisId;
            String evaluationSessionId = "resume-evaluation-" + analysisId;
            AgentResponse response = pythonAgentClient.evaluateResume(
                    new AgentResumeEvaluateRequest(
                            "v1", UUID.randomUUID().toString(), evaluationRunId,
                            userId, evaluationSessionId,
                            "agent.resume.evaluate", "RESUME", resume.getId(),
                            resume.getCandidateId(), resume.getContent(), analysis.getTargetRole(), Instant.now()));
            if (response.code() < 100 || response.code() >= 200) {
                String message = response.error() == null ? "濞戞挸顑呴惇鎵不閳ь剟宕㈤崱姘辨濞寸姴鍢查妵鎴犳嫻? : response.error().message();
                if (response.retryable()) {
                    throw new PythonAgentException(message, null, true);
                }
                persistence.fail(analysisId, message);
                return;
            }
            requireMatchingResponse(
                    response, "lower resume evaluation response did not match request",
                    userId, evaluationSessionId, evaluationRunId);
            if (!persistence.isCancelled(analysisId) && isCurrentResume(resume)) {
                persistence.complete(analysisId, response);
            } else if (!persistence.isCancelled(analysisId)) {
                persistence.cancel(analysisId);
            }
        } catch (RuntimeException error) {
            if (persistence.isCancelled(analysisId)) {
                return;
            }
            if (isRetryable(error) && attempt.getRetryCount() < maxDeliveryAttempts) {
                persistence.recordRetryableFailure(analysisId, safeMessage(error));
                throw error;
            }
            if (!persistence.isCancelled(analysisId)) {
                persistence.fail(analysisId, safeMessage(error));
            }
        }
    }

    private boolean isRetryable(RuntimeException error) {
        return error instanceof PythonAgentException gatewayError && gatewayError.retryable();
    }

    private boolean isCurrentResume(ResumeEntity resume) {
        CandidateEntity candidate = candidateRepository.findById(resume.getCandidateId()).orElse(null);
        return candidate != null && resume.getId().equals(candidate.getCurrentResumeId());
    }

    private void requireSuccess(AgentResponse response, String fallbackMessage) {
        if (response == null || response.code() < 100 || response.code() >= 200) {
            String message = response != null && response.error() != null
                    ? response.error().message() : fallbackMessage;
            throw new PythonAgentException(message, null, response != null && response.retryable());
        }
    }

    private void requireMatchingResponse(
            AgentResponse response, String fallbackMessage,
            String userId, String sessionId, String runId) {
        requireSuccess(response, fallbackMessage);
        if (!userId.equals(response.userId())
                || !sessionId.equals(response.sessionId())
                || !runId.equals(response.runId())) {
            throw new PythonAgentException(
                    fallbackMessage + ": response identity mismatch", null, false);
        }
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }
}
~~~

#### `enqueue`（第 48 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:48`

1. 第 48 行定义函数；构造 RESUME_ANALYSIS 消息并发布分析 ID/userId。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `process`（第 54 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:54`

1. 第 54 行定义函数；读取任务、判断过期/取消/当前版本，调用 Python 并根据 retryable 决定抛出重试或持久化失败。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `isRetryable`（第 127 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:127`

1. 第 127 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `isCurrentResume`（第 131 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:131`

1. 第 131 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `requireSuccess`（第 136 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:136`

1. 第 136 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `requireMatchingResponse`（第 144 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:144`

1. 第 144 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `safeMessage`（第 156 行）

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:156`

1. 第 156 行定义函数；按源码参数完成 RabbitMQ 拓扑配置、消息构造、发布、消费分发或异步任务处理。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


### 3.5 java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseIndexWorker.java

~~~java
package com.interviewguide.knowledgebase.service;

import com.interviewguide.common.exception.BusinessException;

import com.interviewguide.pythonagent.exception.PythonAgentException;
import com.interviewguide.pythonagent.mapper.PythonAgentClient;
import com.interviewguide.pythonagent.dto.AgentRagDeleteRequest;
import com.interviewguide.pythonagent.dto.AgentRagIndexRequest;
import com.interviewguide.pythonagent.dto.AgentResponse;
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
        // 闁告帞濞€濞呭骸霉娴ｈ　鏌ょ€规瓕灏欑划鈥愁嚕閳ь剚鎱ㄧ€ｎ偅顦ч柨娑樼焸濡诧箓宕氬鎹愬幀闁汇劌瀚Λ顐ゆ閵忕姷绌垮ù鐘侯嚙婵喐绋夊鍛箒闂佹彃绉甸弻濠囧礃濞嗗繐寮抽柛姘灴閸ｆ椽濡?        if (knowledgeBase.hasDeletionRequest()) {
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
                // 闁告帞濞€濞呭酣宕烽妸銉с偟闁稿繈鍎插﹢锟犳⒒閺夋垹纾诲┑顔碱儐閸ㄣ劎鈧懓鏈崹姘舵晬濮橆厾顏搁柣鐐叉濠€鏉库枎闄囩换婊堝礆閺夊灝鏅搁柛蹇嬪劤濞堟垿宕ラ幋锕€娅ら柨娑樺缁楁牜绱掑┑鍛憹闁搞儳鍋涢崯鎾舵閵忕姷绌块柟瀛樺姇婵盯鎮╅懜纰樺亾娴ｇ鍋?                AgentResponse deletion = pythonAgentClient.deleteRag(new AgentRagDeleteRequest(
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
            // 闁告帞濞€濞呭酣鎮╅懜纰樺亾娴ｇ娑ч柤宕囨櫕閺侀亶宕氶悩缁樼彑婵炵繝鑳堕埢濂稿箳閵娿劎绠婚柨娑樼灱閸屻劌顕ｉ弴鐐杭閻犳劑鍎扮粭澶婎嚗濡や礁�?DELETING 閻熸洖妫涘ú濠囧箣?FAILED�?            if (latest != null && !latest.hasDeletionRequest()) {
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
~~~

#### `index`（第 38 行）

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseIndexWorker.java:38`

1. 第 38 行定义函数；构造 KNOWLEDGE_BASE_INDEX 消息并发布知识库 ID/userId。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


#### `process`（第 44 行）

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseIndexWorker.java:44`

1. 第 44 行定义函数；读取任务、判断过期/取消/当前版本，调用 Python 并根据 retryable 决定抛出重试或持久化失败。
2. 函数体内每条赋值、条件、消息发送、Python 调用、数据库更新和异常分支均保留在上方当前源码代码块中，按源码顺序执行。
3. 成功路径提交消息/任务状态；不可重试的校验、契约、权限和删除状态错误被确认或写入 FAILED；可恢复依赖异常继续抛给监听器。


### 3.6 java-backend/src/main/resources/application.yml

~~~yaml
spring:
  application:
    name: interview-guide-backend
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/interview_agent}
    username: ${DATABASE_USERNAME:postgres}
    password: ${DATABASE_PASSWORD:}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  sql:
    init:
      mode: always
      schema-locations: classpath:db/web-source-metadata.sql
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:interview}
    password: ${RABBITMQ_PASSWORD:}
    listener:
      simple:
        default-requeue-rejected: false
        retry:
          enabled: true
          max-attempts: ${AGENT_RABBIT_RETRY_MAX_ATTEMPTS:2}
          initial-interval: ${AGENT_RABBIT_RETRY_INITIAL_INTERVAL_MS:1000}
          multiplier: ${AGENT_RABBIT_RETRY_MULTIPLIER:2.0}
          max-interval: ${AGENT_RABBIT_RETRY_MAX_INTERVAL_MS:10000}

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true

agent:
  system-knowledge-base-ids: ${AGENT_SYSTEM_KNOWLEDGE_BASE_IDS:}
  file-storage:
    root: ${AGENT_FILE_STORAGE_ROOT:./data/files}
  pdf-font-path: ${AGENT_PDF_FONT_PATH:}
  python:
    base-url: ${PYTHON_AGENT_BASE_URL:http://localhost:8000}
    connect-timeout: ${PYTHON_AGENT_CONNECT_TIMEOUT:2s}
    # 下层一次物理模型请求最多 120 秒，网络重试最多 5 次；异步消费者必须
    # 等到下层返回标准错误 JSON，才能把具体失败原因写回任务与前端。
    read-timeout: ${PYTHON_AGENT_READ_TIMEOUT:11m}
  reliability:
    max-attempts: ${AGENT_MAX_ATTEMPTS:2}
    backoff-millis: ${AGENT_RETRY_BACKOFF_MILLIS:200}
  async:
    core-pool-size: ${AGENT_ASYNC_CORE_POOL_SIZE:4}
    max-pool-size: ${AGENT_ASYNC_MAX_POOL_SIZE:16}
    queue-capacity: ${AGENT_ASYNC_QUEUE_CAPACITY:100}
  rate-limit:
    requests-per-minute: ${AGENT_RATE_LIMIT_REQUESTS_PER_MINUTE:60}
~~~

## 4. 两个发布点与异常处理

### 4.1 简历分析

`ResumeAnalysisService.submit` 创建 `resume_analyses` 的 PENDING 任务后调用 `ResumeAnalysisWorker.enqueue`。消费者读取任务和简历，确认 current_resume 后依次调用 Python `/v1/agent/resume/activate` 与 `/v1/agent/evaluate/resume`。任务不存在、取消、简历被替换直接返回；Python retryable 错误写入 retryable failure 后抛出，Spring listener 重试；不可重试响应调用 `persistence.fail`。

### 4.2 知识库索引

`KnowledgeBaseService.persistDocument` 保存 PENDING 知识库后调用 `KnowledgeBaseIndexWorker.index`。消费者验证 owner、删除状态和 PROCESSING 状态，再调用 Python `/v1/agent/rag/index`。迟到索引发现知识库已删除时调用 `/v1/agent/rag/delete`。业务错误和不可重试 Python 错误不重投；暂时性异常交给 listener retry。

### 4.3 机制矩阵

| 情况 | 处理 |
|---|---|
| 空消息/缺字段 | Consumer 记录错误并返回 |
| resourceId 格式错误 | 捕获 `NumberFormatException`，记录并确认 |
| 任务过期/取消/资源删除 | Worker 直接返回，不重试 |
| Python retryable | 抛 `PythonAgentException`，由 Spring retry 重新投递 |
| Python 不可重试/业务错误 | 写 FAILED 或取消后确认 |
| 超过最大尝试次数 | 主队列 DLX 转入死信队列 |
| JSON 反序列化/消息转换失败 | `consume` 尚未进入前由 Spring AMQP 抛出，按 listener retry 处理，耗尽后进入 DLQ |
| Rabbit 发送失败 | `convertAndSend` 异常向上抛出，由业务层记录失败 |

## 5. 审核结论

RabbitMQ 发布方和处理方均为 Java；Python 只处理 Java HTTP 调用。拓扑持久化、JSON 转换、任务字段校验、过期幂等、retryable 分类、退避重试和死信均有源码实现。

## 6. 按函数逐行核对（补充索引）

本节把 RabbitMQ 相关函数的每一条可执行语句与源码行号建立对应关系，便于从文档反查实现；空行、import 和 package 声明不改变运行逻辑，故不单独列为函数行为。

### 6.1 `RabbitTaskConfiguration`（`infrastructure/messaging/RabbitTaskConfiguration.java`）

| 行号 | 逐行解释 |
|---:|---|
| 19-23 | 定义主交换机、主队列、routing key、死信交换机和死信队列的固定名称；发布方和监听器必须使用同一组常量。 |
| 25-26 | `@Bean` 将 `agentWorkExchange` 注册到 Spring；`new DirectExchange(EXCHANGE, true, false)` 表示持久化、非自动删除的 direct exchange。 |
| 28-33 | `agentWorkQueue` 创建持久化、非排他、非自动删除的主队列；`x-dead-letter-exchange` 和 `x-dead-letter-routing-key` 指定拒绝/重试耗尽后的投递位置。 |
| 35-39 | `agentWorkBinding` 接收 Spring 注入的队列和交换机，用 routing key 建立主队列绑定；没有该绑定时消息不会进入执行队列。 |
| 41-42 | 创建持久化死信交换机，专门承接主队列无法处理的消息。 |
| 44-45 | 创建持久化死信队列；该队列没有业务监听器，便于运维人工检查和补偿。 |
| 47-50 | 将死信队列绑定到死信交换机，并使用同一个 routing key，使主队列的 DLX 参数能够准确路由。 |
| 53-54 | 注册 Jackson JSON 转换器；`AgentWorkTaskMessage` 在发送时序列化，消费时反序列化。 |
| 56-61 | 创建 `RabbitTemplate` Bean，注入连接工厂和 JSON 转换器；第 59 行设置消息转换器，第 60 行返回模板供两个 Worker 发布。 |

### 6.2 `RabbitAgentWorkConsumer.consume`（`infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39`）

构造器 `RabbitAgentWorkConsumer`（16-19 行）逐行完成依赖注入：第 16 行接收简历 Worker 和知识库 Worker，第 17-18 行把两个参数保存到 final 字段，第 19 行结束构造器；它本身不连接 Rabbit，只为 `consume` 准备分派目标。

| 行号 | 逐行解释 |
|---:|---|
| 22 | `@RabbitListener` 将方法绑定到配置队列，默认值是 `interview.agent.work.execute`。 |
| 23 | 定义消费入口，Spring 将 JSON 消息转换为 `AgentWorkTaskMessage` 参数。 |
| 24 | 同时检查消息对象、taskType、resourceId、userId；任一为空都视为非法消息。 |
| 25-27 | 记录非法消息并直接返回；返回表示监听器正常结束，消息被确认，不会无限重投。 |
| 29 | 对任务类型做分支分派。 |
| 30-31 | `RESUME_ANALYSIS` 分支把 resourceId 转为 Long，调用简历 Worker 的 `process`。 |
| 32-33 | `KNOWLEDGE_BASE_INDEX` 分支把 resourceId 原样传给知识库 Worker 的 `process`。 |
| 34 | 未知 taskType 只记录错误，不调用任何业务处理器。 |
| 36-39 | 捕获 resourceId 不是数字导致的 `NumberFormatException`，记录并结束；该消息属于协议错误，不应重试。 |

### 6.3 `AgentWorkTaskMessage`（`infrastructure/messaging/AgentWorkTaskMessage.java:5-8`）

| 行号 | 逐行解释 |
|---:|---|
| 5 | record 定义三个不可变消息字段：任务类型、可重新查询的资源 ID、用户 ID；不把简历正文或 JPA 实体放入队列。 |
| 6-7 | 定义当前仅支持的两种 taskType 常量，发布方和消费方用同一常量避免字符串拼写分叉。 |

### 6.4 `ResumeAnalysisWorker.enqueue`（`resume/service/ResumeAnalysisWorker.java:48-52`）

构造器 `ResumeAnalysisWorker`（31-46 行）接收 Python 客户端、分析持久化服务、三个 Repository、`RabbitTemplate` 和 listener 最大尝试次数；第 39-44 行保存依赖，第 45 行用 `Math.max(1, maxDeliveryAttempts)` 防止配置为 0，第 46 行结束构造。构造器不发布消息，真正发布从 `enqueue` 第 48 行开始。

| 行号 | 逐行解释 |
|---:|---|
| 48 | 定义异步发布函数，参数是分析任务主键和用户 ID。 |
| 49 | 调用 `RabbitTemplate.convertAndSend`，第一个参数指定主交换机。 |
| 50 | 第二个参数指定 `agent.work.execute` routing key。 |
| 51 | 构造 `RESUME_ANALYSIS` 消息，将 Long 分析 ID 转成字符串；JSON 转换器随后序列化该 record。 |
| 52 | 结束发送调用；连接/序列化异常会向上抛给创建任务的业务层处理。 |

### 6.5 `ResumeAnalysisWorker.process`（`resume/service/ResumeAnalysisWorker.java:54-124`）

| 行号 | 逐行解释 |
|---:|---|
| 54-55 | 定义消费处理函数，并按 analysisId 从 PostgreSQL 查询分析任务；找不到时得到 null。 |
| 58-59 | 任务不存在或已取消时直接返回，识别并丢弃迟到消息。 |
| 61-63 | 查询关联简历；简历不存在同样直接返回，避免对不存在资源重试。 |
| 65-67 | `isCurrentResume` 校验该简历仍是候选人的当前版本；被替换时调用 `persistence.cancel` 并返回。 |
| 69-71 | `beginAttempt` 原子地开始一次处理；返回 null 代表状态已被其他执行或已结束，直接返回。 |
| 73 | 进入 try，后续 Python、持久化和解析异常统一进入 catch。 |
| 74-81 | 生成 memory activation 的 run/session ID，组装请求并调用 Python `/v1/agent/resume/activate`。 |
| 82-84 | `requireMatchingResponse` 同时检查响应成功码和 user/session/run 身份，防止串任务。 |
| 85-87 | 激活后再次检查当前简历版本；期间被替换则取消本次分析。 |
| 89-96 | 生成 evaluation run/session ID，组装简历正文、目标职位等字段并调用 Python `/v1/agent/evaluate/resume`。 |
| 97-103 | 对非 2xx 业务响应提取错误文本；`retryable` 为真时抛出可重试异常，否则写入 FAILED 并正常结束。 |
| 105-107 | 再次校验 Python 响应的身份字段。 |
| 108-112 | 只有任务未取消且仍对应当前简历才保存成功结果；否则取消或保持已取消状态。 |
| 113-116 | 捕获所有运行时异常；若任务已取消则直接返回，避免覆盖取消结果。 |
| 117-120 | 可重试异常且 retryCount 未达上限时记录 retryable failure 并重新抛出，交给 Spring listener 重试。 |
| 121-124 | 达到上限或不可重试时将分析写为 FAILED；不再向 Rabbit listener 抛出，消息因此被确认。 |

### 6.6 `ResumeAnalysisWorker` 辅助函数

| 函数/行号 | 逐行解释 |
|---|---|
| `isRetryable`（127-129） | 只把 `PythonAgentException` 且 `retryable()` 为真归类为临时依赖错误，业务校验异常不重试。 |
| `isCurrentResume`（131-134） | 按 candidateId 查询候选人；只有候选人存在且 currentResumeId 等于简历 ID 才返回 true。 |
| `requireSuccess`（136-141） | null 或非 100-199 响应构造 `PythonAgentException`；错误体存在时使用其 message，并继承 retryable 标志。 |
| `requireMatchingResponse`（144-153） | 先调用 `requireSuccess`，再逐字段比较 userId/sessionId/runId；任意不一致都抛不可重试身份错误。 |
| `safeMessage`（156-160） | 取异常消息；没有消息时使用异常类名，有消息时截断到 500 字，避免把超长堆栈写进任务表。 |

### 6.7 `KnowledgeBaseIndexWorker.index`（`knowledgebase/service/KnowledgeBaseIndexWorker.java:38-42`）

构造器 `KnowledgeBaseIndexWorker`（27-36 行）逐行接收并保存 Python 客户端、状态持久化服务、知识库 Repository 和 `RabbitTemplate`；这些依赖仅用于后续 `index` 发布和 `process` 消费处理。

| 行号 | 逐行解释 |
|---:|---|
| 38 | 定义知识库异步索引发布函数。 |
| 39 | 使用同一 `RabbitTemplate` 将消息发送到主交换机。 |
| 40 | 使用公共 routing key，让消息进入执行队列。 |
| 41 | 构造 `KNOWLEDGE_BASE_INDEX` 消息，只传知识库 ID 和 userId。 |
| 42 | 结束发送；发布异常向上交给上传/重向量化业务处理。 |

### 6.8 `KnowledgeBaseIndexWorker.process`（`knowledgebase/service/KnowledgeBaseIndexWorker.java:44-100`）

| 行号 | 逐行解释 |
|---:|---|
| 44-45 | 定义消费函数并查询知识库实体。 |
| 47-49 | 源文档已删除时直接返回，确认迟到消息。 |
| 50-52 | 比较 ownerId；不属于当前用户抛 `KNOWLEDGE_BASE_ACCESS_DENIED`，由外层按业务错误确认。 |
| 53-58 | 删除请求存在或 `markIndexing` 返回 false 时返回；前者避免对删除对象建索引，后者防止并发重复处理。 |
| 59-64 | 进入 try，生成 request/run/session ID，组装正文、知识库 ID、文件名和时间戳，调用 Python `/v1/agent/rag/index`。 |
| 65-73 | 检查响应 code；写入索引失败状态；只有 retryable 响应才抛出异常交给 listener，否则 return 确认消息。 |
| 74-85 | 重新查询最新实体；若期间删除，调用 Python `/v1/agent/rag/delete` 清理向量，否则解析 answer 中的 chunk 数并标记 INDEXED。 |
| 86-90 | 捕获运行时异常并把仍存在且未删除的知识库标记为索引失败。 |
| 91-98 | 只让临时下层依赖错误继续抛出；业务异常和不可重试 Python 异常 return，避免死信队列被无效消息填满。 |

### 6.9 配置与部署中的 RabbitMQ 行为

`java-backend/src/main/resources/application.yml:19-32` 中，第 19-23 行读取 host/port/用户名/密码；第 24-26 行配置 simple listener 且关闭 rejected message 的默认重新入队；第 27-32 行打开重试，默认最多 2 次，间隔 1000ms、倍增 2、最大 10000ms。`infrastructure/docker-compose.yml:30-40` 启动 RabbitMQ 3.13 management 镜像，第 33-34 行设置默认账号，第 36-39 行用 `rabbitmq-diagnostics -q ping` 做健康检查。`docker-compose.yml:77-98` 将主机、账号和密码传给 Java，并要求 RabbitMQ healthy 后再启动 Java；这些是部署可用性检查，不是业务消息处理器。

### 6.10 发布方的上游调用点

| 文件/行号 | 逐行解释 |
|---|---|
| `ResumeAnalysisService.submit:48-54` | 第 48-51 行取消同一简历的旧活动分析，防止旧消息覆盖新结果；第 52 行创建新的 PENDING 分析；第 54 行调用 `worker.enqueue` 发布 Rabbit 消息。 |
| `ResumeAnalysisService.submit:55-59` | 第 55 行返回任务视图；第 56-58 行捕获发布异常，将分析写为 FAILED 后重新抛出，前端能看到失败而不是永久 PENDING。 |
| `KnowledgeBaseService.persistDocument:104-120` | 第 104-118 行生成 ID、构造并保存知识库原文；第 120 行调用 `indexWorker.index` 发布向量索引任务。 |
| `KnowledgeBaseService.persistDocument:121-125` | 发布失败时第 121-123 行把向量状态写为失败并重新抛错，第 125 行仅在消息成功发出后返回视图。 |
| `KnowledgeBaseService.revectorize:202-209` | 第 202-207 行校验资源、拒绝删除中的知识库并置为待索引；第 209 行重新发布同一知识库的索引任务。 |
| `KnowledgeBaseService.revectorize:210-213` | 第 210-212 行处理发布异常并写入失败状态，第 213 行将异常继续交给 API 层。 |

### 6.11 依赖和容器连接配置

`java-backend/pom.xml:41-44` 声明 `spring-boot-starter-amqp`：第 41 行开始依赖块，第 42 行指定 Spring Boot 组织，第 43 行选择 AMQP starter，第 44 行结束依赖。该依赖提供 `RabbitTemplate`、`@RabbitListener` 和 Spring AMQP listener 容器，但不会自动创建本项目的交换机/队列；拓扑仍由 `RabbitTaskConfiguration` 的 Bean 完成。
