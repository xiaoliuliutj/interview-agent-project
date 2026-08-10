# 当前重构落地状态

## 项目边界

系统由 React 前端、Java 上层和 Python 下层 Agent 组成。当前只保留三条业务线：简历分析、文本面试、知识库管理与面试内部 RAG。知识库不提供独立聊天入口，面试日程也不属于当前产品范围。

## Python 下层

- 面试 Agent：规划、评分、路由、出题、会话记忆与总结。
- 评分节点只使用 `interview-coach` Skill 与 `evaluation.md`，路由节点使用 `routing.md`；两者都不调用 RAG。
- 流程严格为：评估 → 决定追问/换题/换阶段 → 确定题目方向 → 读取 RAG 缓存 → 缓存未命中时联合检索系统库和用户库 → 生成题目并缓存证据。
- RAG 证据、评分、记忆和工具轨迹只保留在 Python 下层。
- 简历评价完成后只会写入已激活的当前简历版本的用户长期画像；旧任务迟到返回时无法覆盖最新版画像。

## Java 上层

- 负责身份、业务会话、简历文件、知识库上传/管理、异步任务、重试、并发与对外展示。
- 通过固定 JSON 契约调用 Python，不在 Java 重复实现 Agent 决策。
- 知识库接口只负责上传、索引、列表、分类、下载、删除和重建索引；检索仅由文本面试内部使用。

## 部署与验证

`infrastructure/docker-compose.yml` 提供完整虚拟机部署。Python 测试使用 `D:\Anaconda\envs\inter-guide\python.exe -m pytest tests -q -p no:cacheprovider`；Java 和容器集成测试在虚拟机执行。
## 本轮面试流程与报告改造

- 已实现六阶段硬边界：总题量最多 20（含追问）；项目、基础、场景各最多 4 个主问题、每题最多 2 次追问；算法最多 2 题且第二题要求首题评分低于 40 分。
- 已实现先评估再路由，回答较好时不会自动追问；评估摘要、题目计数和下一题通过上层返回前端。
- 已实现每轮问答记录、最终综合评估、前端评估页和评估 PDF；数据库字段迁移位于 `infrastructure/postgres/init/003-interview-report-upgrade.sql`。
- PDF 支持挂载字体和 Docker 内 `fonts-noto-cjk` 的 TTC 字体；已有数据库卷升级后需手动执行迁移脚本。
- Python 测试已通过 52 项，前端 TypeScript 检查已通过；Java 容器构建需在虚拟机执行。
