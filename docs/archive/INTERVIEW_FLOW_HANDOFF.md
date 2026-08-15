# 模拟面试流程交接说明

## 1. 文档目的

本文件用于在不同对话或不同开发环境之间交接当前“文本模拟面试”改造工作。它只记录已经核对过的代码状态、完成情况和后续验证步骤；未运行验证的内容不得标记为完成。

## 2. 当前工作区状态

- 项目目录：`D:\实习\项目\interviewGuide`。
- 当前工作树存在一批**未提交**修改，覆盖 Python Agent、Java 上层、前端、数据库迁移和说明文档。
- 这些修改已经实现了大部分目标，但 Java 未在本机完成构建，最终仍须以虚拟机 Docker 构建和真实会话测试为准。
- `python-agent/config/rag/sources/java-interview-knowledge-base-assets/` 是未跟踪的知识库资料资源。提交前需要确认它是否应纳入仓库；不要因为本次面试流程修复而删除它。

## 3. 已写入工作树、尚待运行验证的内容

### 3.1 题量和阶段计数

- 前端已移除用户填写题量的入口和请求字段。
- Java 创建会话时固定保存总题量预算 `20`；Python 初始化时也强制使用 `20`，因此旧接口即使携带 `questionCount` 也不会改变预算。
- 预算含开场题、主问题和追问。前端显示当前阶段、当前阶段主问题数、累计主问题数、当前主问题追问数、已发出题数和总预算。
- `totalPrimaryQuestionCount` 已通过 Python → Java → 前端链路传递并落库。

### 3.2 对话和逐轮评估

- 前端提交回答后不再通过重新初始化覆盖整个消息数组；应保留“面试官问题 → 用户回答 → 回答评估 → 下一道问题”的完整顺序。
- 会话详情接口返回已落库的历史轮次和当前未回答问题；前端恢复会话时据此重建对话。
- Java 的 `InterviewTurnEntity` 已保存阶段、问题、回答、评估摘要和分数；历史详情页展示每轮评估。

### 3.3 结束语和最终评估

- Python 会话完成后生成 `InterviewSummary`：综合分、总评、优点、不足和建议；总结模型失败时使用基于逐轮得分的降级报告。
- 下层返回 `finalEvaluation`，Java 写入 `finalEvaluationJson`，详情接口与简历历史接口解析并返回。
- 前端完成页保留完整对话，展示“本次面试已结束”提示、最终评估和 PDF 下载按钮。

### 3.4 PDF

- 面试报告使用 `InterviewReportPdfService` 导出问答和最终评估。
- Docker Java 镜像已安装 `fonts-noto-cjk`，报告服务会优先读取外部配置字体，缺失时尝试镜像内 Noto CJK 字体。
- 数据库迁移 `infrastructure/postgres/init/003-interview-report-upgrade.sql` 已新增计数、逐轮评估和最终报告字段。

## 4. 本轮已完成的关键修复

以下规则已经写入代码，并由 Python/Java 回归测试覆盖。

### 4.1 严格阶段状态机

`python-agent/app/agent/interview/service.py` 现在把阶段推进与整场结束分离：三个中间阶段不会再通过 `END_INTERVIEW` 跳过后续阶段；CODING 的第二题由首题评分确定性触发。当前硬规则为：

| 阶段 | 主问题规则 | 追问规则 | 离开阶段规则 |
| --- | --- | --- | --- |
| OPENING | 固定 1 题，自我介绍 | 0 | 回答后重规划，进入 PROJECT |
| PROJECT / FUNDAMENTAL / SCENARIO | 至少 2、最多 4 | 每道最多 2 次 | 仅在至少 2 道主问题后，允许模型选择继续或下一阶段；不得直接结束整个面试 |
| CODING | 默认 1 道；第一题得分 `< 40` 时强制第 2 道 | 0 | 第一题不严重失分或第 2 题完成后，直接完成会话 |
| SUMMARY | 不生成伪问题 | 无 | 生成结束语和最终评估 |

总题量达到 20 时强制结束；用户显式“提前交卷”也可结束。

### 4.2 追问与换题决策

决策顺序必须保持：**评分 → 路由 →（必要时）RAG 检索 → 出题**。

- 评分 `≤ 60` 或存在明确弱项，且当前主问题追问次数未达 2，才允许 `FOLLOW_UP`。
- 回答较好时，优先在当前阶段切换新的主问题；不能因为路由模型错误选择 `FOLLOW_UP` 就直接跳阶段。
- 未达到阶段最低主问题数时，若模型错误返回 `NEXT_STAGE`，代码兜底必须从当前阶段的未覆盖主题中选择下一道主问题，不能把下一阶段主题带入当前阶段。
- 每个主题最多出现 3 次；达到阶段 4 个主问题后，当前第 4 题仍可完成合法追问，再进入下一阶段。

### 4.3 计划归一化

`python-agent/app/agent/interview/agent.py` 的规划结果应在代码中归一化：

- PROJECT、FUNDAMENTAL、SCENARIO：`max_primary_questions=4`、`max_followups_per_question=2`；数值是能力上限，不代表必须问满。
- CODING：`max_primary_questions=2`、`max_followups_per_question=0`。
- 模型生成的计划只决定候选主题、难度和策略，不决定每阶段最终题数。

### 4.4 PDFBox 兼容性

`InterviewReportPdfService` 已改用 `TrueTypeCollection.processAllFonts(...)` 取得第一个可用字体后再调用 `PDType0Font.load(...)`，并补充普通中文字体和 TTC 字体集合两种导出测试。

## 5. 受影响的核心文件

| 链路 | 主要文件 |
| --- | --- |
| Python 状态机 | `python-agent/app/agent/interview/service.py` |
| Python 计划归一化 | `python-agent/app/agent/interview/agent.py` |
| Python 协议输出 | `python-agent/app/api/application.py`、`python-agent/app/core/contracts.py` |
| Python 路由约束 | `python-agent/config/prompts/interview/routing.md` |
| Java 会话落库 | `java-backend/src/main/java/com/interview/agent/upper/service/InterviewSessionPersistenceService.java` |
| Java API 视图 | `java-backend/src/main/java/com/interview/agent/upper/service/InterviewService.java`、`api/InterviewController.java`、`api/ResumeController.java` |
| Java PDF | `java-backend/src/main/java/com/interview/agent/upper/service/InterviewReportPdfService.java` |
| 前端面试页 | `frontend/src/pages/InterviewPage.tsx`、`components/InterviewChatPanel.tsx` |
| 前端历史页 | `frontend/src/components/InterviewDetailPanel.tsx`、`pages/InterviewHistoryPage.tsx` |
| 数据库 | `infrastructure/postgres/init/003-interview-report-upgrade.sql` |

## 6. 已补充并通过的测试

Python 单测覆盖：

1. OPENING 回答后重规划并进入 PROJECT。
2. 三个中间阶段仅 1 道主问题时，模型返回 `NEXT_STAGE` 仍被代码改为当前阶段 `NEXT_QUESTION`。
3. 中间阶段达到 2 道主问题后，允许模型选择继续或下一阶段，但不允许提前结束整个会话。
4. 低分/明显缺口的回答触发追问；高分回答不触发追问。
5. 第 4 道主问题后的合法追问不会被阶段上限吞掉。
6. CODING 第一题低于 40 分时产生第 2 道题；否则直接完成并产生报告。
7. 总问题数达到 20 时完成并携带 `finalEvaluation`。

前端构建与人工验收口径：

1. 创建后立即显示第一道面试官问题。
2. 每次提交后保留旧问题、用户回答、评估摘要和下一题。
3. 计数与阶段同步刷新，不再固定显示 6 题。
4. 完成页保留对话、结束语和最终评估。
5. 历史详情显示逐轮评分与最终评估。
6. PDF 下载后可打开中文内容。

本轮本机验证结果：Python `58 passed`；Java `9 tests, 0 failures`；前端 TypeScript/Vite 生产构建成功。Docker 与真实模型链路仍需在虚拟机环境做最终演示验收。

## 7. 虚拟机验证顺序

1. 合并代码后先应用数据库迁移：

```bash
cd ~/interviewGuide/infrastructure
docker compose --env-file .env exec -T postgres \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < postgres/init/003-interview-report-upgrade.sql
```

2. 重建全部服务：

```bash
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

3. 检查 Java 和 Python 日志，确认没有 PDFBox 编译错误、数据库列不存在错误或 Agent 协议字段错误：

```bash
docker compose --env-file .env logs --tail=200 java-backend python-agent
```

4. 使用新创建的面试会话验证。旧会话缺少新计数或最终评估字段时，不应用于判定本次改造是否成功。

## 8. 提交纪律

- 代码、迁移和文档完成并验证前不得提交。
- 提交前执行 `git diff --check`，确认不包含 `.env`、API Key、数据库密码或虚拟机本地字体文件。
- 代码提交必须先获得用户确认；文档可按现有约定直接同步。
