# Long-term Memory V5：提取、整合、压缩与可靠性（2026-10-18）

本次基于 GPU 服务器 `/path/to/DOVideo-AI` 的真实工作区继续修改；原有未提交 Memory、前端和视频处理改动均保留。V4 不修改，数据库增量使用 V5。下方折叠内容是早期实现与验收记录，不能替代本节描述的当前行为。

## 当前调用链

```text
AnalysisController → AnalysisDispatchService → RocketMQ VideoAnalysisConsumer
  → AiService.asyncAnalyze / followUp
  → MemoryRequest（服务器确认 userId，currentGoal 为原始用户请求）
  → AgentLoopService.run
      → MemoryService.prepare → MemoryGate（规则，无 LLM）
          → 跳过/异常/零召回：null，保持普通上下文和缓存路径
          → Embedding + 独立 Memory Qdrant → MySQL 复核 owner/scope/status/contentHash
          → semantic similarity + importance + recency → 非空 MemoryContext
      → 原 Video Retrieval → Planner → Executor → Critic + EvidenceVerification
      → 最终 Checkpoint 成功持久化
      → MemoryService.completed：仅通过 Critic 的结果进入 SQL 持久化工作队列
      → 返回最终结果（不等待记忆 LLM、embedding 或压缩）

MemoryMaintenance（每进程最多一个后台 worker）
  → 补偿扫描已完成 Checkpoint 与队列之间的崩溃窗口
  → SQL 租约领取任务 → MemoryExtractionService
      → 用户请求/显式反馈：MemoryLlmExtractor → DeepSeekUtils.memoryJson
      → Plan/Result/Critic：MemoryExtractor 确定性生成 EPISODIC_TASK/PREVIOUS_RESULT
      → 验证 JSON、用户原文、类型、范围、置信度、否定/条件/时间限定和凭据
  → MemoryService.related：合法 SQL 候选 + 向量相关性，最多 6 条
  → MemorySemanticResolver：DUPLICATE / UPDATE / CONTRADICTION / NEW / UNCERTAIN
  → MemoryRepository.mergeResolved（用户行锁 + MySQL 事务 + 索引 outbox）
  → 达到阈值：MemoryConsolidationService.compact（保守无损层级整合）
  → MemoryIndexService：outbox → Embedding → Memory Qdrant；失败退避重试
  → 后续历史依赖问题召回，仍以当前 Video Evidence 为准
```

反馈继续使用原 `/analysis/agent-feedback`；不新增另一套分析 Agent，不改变视频检索和证据核验语义。

## 概念与实际来源

Conversation History 是原始会话过程，含临时要求、中间错误和重试，不等于长期记忆。本实现不把整个聊天记录作为记忆搜索。

Working Memory 是 Query、Plan、视频片段、Critic 和执行状态。Checkpoint 可以为任务恢复保存这些状态，但解决的是“任务执行到哪里”；长期记忆解决“这个用户哪些知识值得跨任务复用”。后台工作队列的暂存载荷是工作数据，完成后清空，不是 `agent_memories` 中的知识。

`GoalContext.userGoal` 没有可靠来源，仍为空；`sessionGoal` 仅追问可能来自用户提供的原始目标；`currentGoal` 为真实请求。正常业务接口尚没有独立可靠 sessionId，仍为 null，不能宣称完整会话管理或自动长期目标推断。`VideoContext.userGoal` 原含义保留。

## LLM 提取的信任边界

- `MemoryLlmExtractor` 只接收用户原始 Query 或显式反馈 comment。ASR、OCR、Memory 和模型结果不作为用户偏好输入。
- 复用同一个 `DeepSeekUtils` / LangChain4j ChatModel、配置、模型执行池；不新增云端模型基础设施。
- LLM 返回 type/content/scope/semanticKey/importance/confidence/source/quote/condition。字段逐项校验；source 由服务器认定，模型 content 不直接落库，采用经过定位的用户原文 quote。
- quote 必须是可信输入中的连续原文；被转述的视频指令、引号内台词、假设、身份猜测不能升级为用户偏好。程序还检查 quote 没有遗漏所在句子的否定、条件和过去时间限定。
- 已知长度/语言/格式槽位根据原文规范化，防止模型编造 `output.length/output.format` 一类复合名称绕过属性更新。条件偏好按条件 hash 使用独立槽位，不覆盖无条件偏好。
- EPISODIC_TASK 来自真正通过 Critic 的目标、Plan 和产物；PREVIOUS_RESULT 来自标题与有限结论摘要，不使用 LLM 猜视频事实。
- `MemorySafety` 在模型调用、队列和写入前拒绝密码、密钥、Bearer/Token、私钥和明显提示注入。该检查偏保守，会有误拒，不是通用 DLP。
- 合法空结果就是没有提取到知识；超时/非法 JSON 可独立配置是否使用规则降级。规则降级保留明确否定和条件，不再把“不喜欢详细”解释成“喜欢详细”。

## 语义关系与事务

MySQL 保留 V4 的确定性 ID、contentHash 精确去重和唯一约束。模型只返回候选序号 targetIndex，由程序映射为真实 memoryId，禁止模型直接选择或编造数据库 ID。关系输出要求置信度至少 0.9。

写入事务重新检查 userId、类型、Scope、条件、ACTIVE 状态和目标 contentHash，目标变化时回滚并由后台重试。模型调用不占数据库行锁。

- DUPLICATE：复用原记录，合并已观察的任务来源，不新增同义记录；否定极性不同不能走重复合并。
- 固定偏好槽位：受信任的明确用户声明可以更新同一属性。
- UPDATE/CONTRADICTION：语义替换还需明确修正意图及受信任来源；不因相似度或更新时间较新就覆盖。
- NEW/UNCERTAIN：未知属性保守保留，不自动撤销原有知识。可靠固定槽位规则仍是独立基础。
- DISABLED、SUPERSEDED、COMPRESSED 均不自动恢复 ACTIVE；旧任务同属性的不同措辞也受退役来源保护。宁可要求用户重新明确表达，也不自动复活已退休的原文。
- 不跨用户、不跨视频、不跨不兼容会话或条件自动合并。

200 条是 ACTIVE 准入上限；达到上限时延迟新独立记忆写入并重试，不通过随机禁用偏好/事实来腾空间。历史状态和墓碑不再按旧上限自动物理删除，因此总存储量不是严格有界，需后续生命周期管理。

## 长期分层压缩

默认关闭；默认在 ACTIVE 达到 150 条时尝试，低于 200 条准入上限。主要对象 EPISODIC_TASK，也支持视频作用域兼容的 PREVIOUS_RESULT。

候选必须同用户、同类型、同 Scope、同主题、足够旧，不能含未解析条件。主题来自服务器对实际 Plan tasks 的 hash，属于保守的同任务主题分组，并非任意跨主题语义聚类。

相同执行计划只写一次，各次任务目标和产物分别保留；程序逐条验证可重建源文本。其它兼容来源使用保守抽取式汇总，不用模型生成无法程序核实的新事实。分层元数据保存 level、sourceMemoryIds、sourceTaskIds；SQL `agent_memory_sources` 保存可递归查询的来源边。

压缩前重新核验所有源记录和内容版本，验证目标类型、范围、主题、内容长度、任务来源和层级；超出 1200 字时缩小批次，不能安全合并就放弃。摘要写入、来源边、源记录 COMPRESSED 状态和 outbox 在同一事务提交；失败全部回滚。用户行锁和确定性摘要 ID 保证多实例只有一个赢家。源原文保留用于追溯，不以减 ACTIVE 数量为由删除偏好、事实或用户反馈。

这属于有来源的无损/抽取式层级整合，不应描述成经过事实证明的自由生成式摘要，也不保证每一组都减少总字节数。

## 异步、恢复和索引补偿

最终 Checkpoint 保存必须先成功，随后只排队，不执行 Memory LLM。记忆失败不把视频任务改成失败。

`agent_memory_jobs` 使用确定性工作 ID、300 秒租约和完成令牌，失败指数退避，最多 5 次后 DEAD；错误只记异常类别，不记凭据或请求原文。DONE 清空 payload，保留去重回执。可通过当前用户的 `/analysis/memory/retry` 重试 DEAD 工作。

数据库时钟统一用于租约、重试、候选年龄判断，避免 JVM/JDBC/数据库时区不一致导致任务永远领取不到。

后台扫描超过 10 秒的已完成 Checkpoint 修复“最终结果已保存、队列尚未保存”的崩溃窗口。恢复时只生成已通过 Critic 的任务/结果记录，不把包装后的 goal 当作可信用户偏好，不重建或猜测丢失的用户偏好。这是工作恢复，不是把 Checkpoint 当长期记忆直接检索。

`agent_memory_index_outbox` 与 Memory 变更同事务写入。后台回读 SQL 最新状态后执行 upsert/delete；失败保留事件并退避；version 条件确认避免误删并发的新事件。可分页请求当前用户的全量重建。Qdrant 丢 collection 后，活动记录可重新创建 collection；非活动记录的 404 删除视为已完成。

MySQL/Qdrant 是最终一致，不是强一致。索引重建窗口可能漏召回；过期索引不能绕过 SQL 的归属、状态和 hash 校验。语义关系和历史召回在索引不可用时仍可保守使用 SQL；普通事实没有向量命中时不会伪造相似度。

## Gate、缓存、Plan 与证据

Gate 保持低成本规则，不调用模型。跳过、失败或没有实际记忆时返回 null，不制造空 MemoryContext。Controller、AiService 和 Dispatch 的普通查询缓存路径不再只因 MEMORY_ENABLED 就失效。

只有真正召回的 Memory 才标记 plan.memoryAugmented。旧个性化结果不会作为普通结果复用；需要历史的请求保守刷新。用户 correctedTasks 标记 userRevised，优先于 Memory 的计划失效策略；Critic 重试不悄悄替换用户指定的任务。

Prompt 区分 Current Task、Agent Memory、Video Evidence；当前明确任务覆盖旧偏好，视频事实优先当前证据。Memory 与压缩摘要都不能生成视频时间戳证据。`EvidenceVerificationService` 仍核对时间区间、ASR/OCR 原文子串和结论绑定；这不是通用语义真实性证明。

## 配置（默认兼容）

| 配置 | 环境变量 | 默认 |
|---|---|---|
| memory.enabled | MEMORY_ENABLED | false |
| memory.llm-extraction.enabled | MEMORY_LLM_EXTRACTION_ENABLED | false |
| memory.llm-extraction.timeout-ms | MEMORY_LLM_EXTRACTION_TIMEOUT_MS | 10000 |
| memory.rule-extraction.enabled | MEMORY_RULE_EXTRACTION_ENABLED | true |
| memory.semantic-consolidation.enabled | MEMORY_SEMANTIC_CONSOLIDATION_ENABLED | false |
| memory.semantic-consolidation.timeout-ms | MEMORY_SEMANTIC_CONSOLIDATION_TIMEOUT_MS | 10000 |
| memory.consolidation.enabled | MEMORY_CONSOLIDATION_ENABLED | false |
| memory.consolidation.trigger-count | MEMORY_CONSOLIDATION_TRIGGER_COUNT | 150 |
| memory.consolidation.batch-size | MEMORY_CONSOLIDATION_BATCH_SIZE | 6 |
| memory.consolidation.min-age-days | MEMORY_CONSOLIDATION_MIN_AGE_DAYS | 7 |
| memory.worker.poll-ms | MEMORY_WORKER_POLL_MS | 2000 |

配置模板见 `.env.example`。打开新能力需要启用对应开关并重启后端；不需要改前端或视频 Agent。MEMORY_ENABLED=false 时后台 worker、提取、索引、压缩、Memory 读写全部跳过。Flyway 的结构迁移是应用升级步骤，不是运行期 Memory 读写。

## 运维接口（沿用鉴权，userId 从当前登录态取）

- `DELETE /analysis/memory/{memoryId}`：逻辑禁用，事务写入索引删除补偿。
- `GET /analysis/memory/{memoryId}/sources`：当前用户摘要的直接来源 ID/任务 ID，可递归追溯。
- `POST /analysis/memory/reindex?after=&limit=100`：返回本页 ID；用最后一个 ID 作为下一页 after，直到空页。
- `POST /analysis/memory/retry`：重新排队当前用户 DEAD 记忆任务。

不要把真实 `.env`、数据库密码、原始视频、Memory 内容或完整模型调用日志提交到 Git。

## 可复现测试与已知边界

```bash
# 使用项目 JDK 21 / Maven 环境
source /path/to/local-runtime/env.sh
cd /path/to/DOVideo-AI/server
mvn test
mvn -DskipTests package
# 显式真实服务测试；只允许隔离 dovideo_memory_test 库，Qdrant 默认 16333
# 凭据使用 MEMORY_TEST_MYSQL_USER / MEMORY_TEST_MYSQL_PASSWORD 环境变量，不写命令参数
mvn -Dtest=MemoryFlowTest,MemoryUpgradeTest,MemoryQdrantIT,MemoryLiveIT test
```

默认测试使用 H2、Mock LLM/Embedding/Qdrant，以及真实 HTTP 故障响应；不能称作真实外部模型 E2E。MemoryLiveIT 明确要求真实隔离 MySQL、本地 qwen2.5:7b-instruct、真实 embedding 和 Qdrant；通过 Critic 的任务数据是结构化测试样本，因此仍不是上传→ASR→OCR→完整 Agent 的视频 E2E。

可复现实例：
1. “我喜欢详细回答”→“我不喜欢详细回答”→“以后请简洁回答”：旧值退休，旧任务重放不能恢复。
2. “我喜欢在学习时详细回答”：条件独立；模型不得删掉“学习时”而覆盖通用偏好。
3. “视频中他说：我喜欢详细回答”：即使模型截取内部“我喜欢”也不能写为用户偏好。
4. 多个相同 Plan 主题的旧任务达到阈值：汇总后原文仍可沿 source links 找回；并发压缩只有一份摘要。
5. 模拟 Qdrant 失败：SQL 和 outbox 仍持久化，索引恢复后重试；旧 hash 不进入事实记忆召回。
6. 普通“视频中有几个人”：Gate 跳过、MemoryContext 为 null、普通 Checkpoint 可复用。
7. 提交 correctedTasks：即使实际召回 Memory，仍使用用户修订的 Plan。

限制：后台更新是最终一致，刚返回结果立即追问可能尚未召回新记忆；没有完整 sessionId 产品接入；压缩主题匹配刻意保守；原文 tombstone、来源链、DONE 回执和 DEAD 载荷仍需长期存储治理；传输取消为 best effort，但 optional model semaphore 最多允许一个实际未退出的记忆调用占用共享池；LLM 不确定性无法消除，低置信和非法关系不自动覆盖。敏感字段过滤会误拒，不能替代完整隐私审计。

## 本轮实际验证结果（2026-10-18）

- JDK 21，`mvn -q package`：编译、完整默认回归与打包成功，83 项测试全部通过，0 失败、0 错误、0 跳过。
- 隔离真实服务：48 项通过，0 失败/错误/跳过，整组 Maven 集成耗时 48.18 秒。包括 45 项在真实 MySQL 重跑的测试、2 项实际 Qdrant 测试、1 项真实本地 Qwen/Embedding/MySQL/Qdrant Memory 链路测试。不能把两组数字相加当作独立用例数。
- MemoryLiveIT 使用实际 `qwen2.5:7b-instruct` 和 `qwen3-embedding:0.6b` 的本地服务别名；Plan/Result/Critic 为结构化测试样本。没有冒充上传→ASR→OCR→完整视频 Agent 的 E2E。
- 后端实际重启通过，业务库 Flyway V5 成功，V4 源文件 SHA256 与改动前一致，前后端/ASR/模型网关/两个模型服务均正常。
- 本次未修改私密 `.env`。新 LLM 提取、语义整合、压缩开关仍默认 false；真实集成显式启用验证。原有 MEMORY_ENABLED 状态保留。
- 历次测试中发现并修复数据库/JDBC 时钟差异、模型编造目标 UUID、空/复合 semanticKey、截断 quote、旧版已应用修订 Plan 标记及来源树导致 Prompt 膨胀等问题。最终未留失败用例。
- 主 Agent Prompt 只接收有界的 Memory 投影，最多约 5000 字符、每条最多 8 个来源 ID；完整来源保留在 SQL。只评分而无文字的反馈不调用记忆 LLM。

验收日志位于服务器 `/path/to/local-runtime/memory-upgrade-package.log`、`memory-upgrade-real-integration.log`，结构化计数在同目录 `memory-upgrade-real-report.json` 与 `memory-upgrade-release-report.json`。层级来源只保存直接边并递归追溯，避免后代列表无限膨胀；压缩失败不阻断独立记忆写入。旧文档以下仅为历史记录。

<details>
<summary>V4 阶段历史文档与早期验收记录（非当前实现）</summary>

# Long-term Memory：真实实现与运行说明

## 本轮检查结果

检查目录 `/path/to/DOVideo-AI`，基线为 `df23f36`。开始时 `git status` 干净，最近 10 次提交及当前源码没有长期记忆模块，也没有本文件和 `memory-interview-qa.md`。因此没有可复用的 Memory 半成品；复用了原来的视频处理、混合检索、模型调用、鉴权和 Checkpoint 能力，没有重新 clone。

真实入口：`AnalysisController` → `AnalysisDispatchService` → RocketMQ `VideoAnalysisConsumer` → `AiService.asyncAnalyze` → `resolveContext`（ASR/OCR、内容复用）→ `AgentLoopService.run`。追问从 `AiService.followUp` 进入同一个 AgentLoop；反馈从原 `/analysis/agent-feedback` 和修订入口接入。

## 完整数据流

```text
用户 Query / 原 analysisGoal（源码对应 VideoContext.userGoal）
  → MemoryRequest：从所属 MediaFile 获取 userId，生成稳定 sourceTaskId
  → GoalContext：userGoal / sessionGoal / currentGoal
  → MemoryGate（普通视频事实查询跳过记忆 I/O）
  → 查询 embedding → 独立 Qdrant Memory collection → MySQL 回读 / 权限与状态复核
  → similarity + importance + recency 排序 → Top-K MemoryContext
  → 原 LongVideoContextService.selectRelevant
  → 原 Hybrid Video Retrieval / video_chunks Qdrant
  → 原 Planner → Executor → Critic / ASR、OCR 证据核验 → 必要时补证据与重跑
  → Final Result / 原 Checkpoint
  → 仅 Critic 通过：MemoryExtractor → MySQL merge / supersede / 容量限制
  → Memory embedding → 独立 Memory collection upsert / 旧索引清理
```

`AgentLoopService` 新增接收 `MemoryRequest` 的重载；旧重载仍可用，没有可靠用户身份时不做记忆读写。准备记忆在视频执行预算开始前，更新记忆在该预算结束后，避免可选 I/O 耗尽原 Agent 的执行时间。没有重写 Planner、Executor 或 Critic。

## Goal Context 与身份

`VideoContext.userGoal` 继续保留原含义：当前分析目标，也就是原 analysisGoal 的等价字段，不改任务键的目标文本。

`GoalContext` 轻量区分：

| 字段 | 来源 |
| --- | --- |
| userGoal（用户长期目标） | 当前没有可靠来源，保持 null，不推测用户画像 |
| sessionGoal（会话目标） | 分析任务为 null；追问可使用用户提交的原始分析目标 |
| currentGoal（当前任务） | 原始分析 Query 或原始追问 question |

现有接口没有可靠的 sessionId，所以正常任务的 sessionId 为 null，不伪造会话。尤其不能用追问包装文本中的“继续追问”自动触发 Gate：Gate 只读原始 question。

`userId` 由现有鉴权及媒体归属链确认，从服务端 `MediaFile.userId` 获取；不从模型结果、Memory 内容或客户端自报 userId 推导。后台消费也从媒体记录解析所有者，不依赖 HTTP 线程变量。

## Conversation History、Working Memory 与 Checkpoint

Conversation History 是会话中的原始往返，混有临时问题、失效结论和重试，不等于可跨会话复用的知识。本实现不把整段聊天、追问包装文本或完整报告写入长期记忆。

Working Memory 是本次 Query、Plan、Video Evidence、Critic Feedback 和执行状态。它们继续由现有 AgentState 和视频上下文承载，不作为独立长期记忆持久化。现有 Checkpoint 仍可为了恢复任务保存这些工作状态；这是工作流恢复数据，不是知识库。

Checkpoint 按媒体、目标、模式保存可继续执行的位置，解决“从哪一步恢复”。Long-term Memory 按用户、作用域和知识类型保存筛选后的偏好、历史任务和摘要，解决“以前知道什么”。二者没有共用表、没有把 Checkpoint JSON 导入记忆，也没有删除 Checkpoint。

`VideoContext.memory` 是请求内辅助上下文，标记 `@JsonIgnore`；保存视频上下文时继续使用原三参数构造，不把召回记忆存入视频 Checkpoint。

## 持久化与类型

Flyway `V4__create_agent_memories.sql` 新建 MySQL `agent_memories`，包含：

`memoryId / userId / type / content / sourceTaskId / sourceVideoId / importance / createdAt / updatedAt / status / scope / sessionId / semanticKey / contentHash`。

| 类型 | 实际保存内容 | 常用作用域 |
| --- | --- | --- |
| USER_PREFERENCE | 用户明确表达的回答长度、语言、组织格式等 | GLOBAL_USER |
| EPISODIC_TASK | 用户完成过的任务、最多三项执行内容和产物标题 | GLOBAL_USER |
| IMPORTANT_FACT | “请记住：我……”这种明确声明的事实 | GLOBAL_USER |
| PREVIOUS_RESULT | 已通过 Critic 的标题及最多三条结论摘要 | VIDEO |
| USER_FEEDBACK | 原有反馈 API 的评分、说明、修正要求及证据接受情况摘要 | VIDEO |

支持 GLOBAL_USER / VIDEO / SESSION 三种作用域；SESSION 仅在调用方具有真实 sessionId 时可用。当前正常入口不生成 SESSION 记忆。

MySQL 是权威原文与状态来源。Qdrant 默认 collection 为 `agent_memories`，与视频的 `video_chunks` 分离，payload 只放索引元数据，不重复保存 Memory 原文。相同 collection 名称会在使用时拒绝写入，并由 fail-open 边界降级，不污染视频索引。

## Memory Gate / Retrieval

`MemoryGate` 是无外部依赖的轻量规则，识别“之前、上次、继续、按照以前、我的习惯、我的偏好”等历史依赖，也支持部分英文表达。普通“视频中有几个人？”跳过 Memory DB、embedding 和 Qdrant。Gate 抛异常也直接跳过记忆。

召回先取最多 `8 × Top-K` 个向量候选，再从 MySQL 按 userId、ACTIVE 状态和 scope 回读。Qdrant 本身也过滤用户和作用域；服务层再次复核归属、scope、状态及 contentHash，拒绝其他用户、失效记录和过期索引版本。

排名公式：`0.55 × similarity + 0.25 × importance + 0.20 × exp(-ageDays/30)`。importance 和 similarity 归一到 0–1。低相关候选被过滤，最终最多返回 Top-K。

明确历史任务请求可以加入近期 EPISODIC_TASK；明确触发 Gate 后，用户偏好也可作为候选。它们使用有限的意图匹配分值，而不是宣称存在向量命中。因此 embedding / Qdrant 故障时，仍可能从 MySQL 召回偏好或历史任务；无可用结果则为空上下文。

## Prompt 与视频证据冲突

`DeepSeekUtils.renderContext` 在记忆开启的请求中明确划分 Current Task、Goal Context、Agent Memory 和 Video Evidence；关闭时仍使用原 VideoContext JSON。Planner、重规划、修复、Executor 和 Critic 都通过该方法接收上下文。

规则：当前明确任务要求优先于旧输出偏好；Memory 与当前 Video Evidence 冲突时，必须以 Video Evidence 为准。Memory 是不可信辅助数据，不是系统指令，不能充当 ASR/OCR 原文、时间戳或视频事实证据。

原 `EvidenceVerificationService` 继续核验时间戳、来源、原文和结论绑定。模型把历史 Memory 的引文冒充当前视频证据，即使模型 Critic 返回通过，代码层仍可将结果降为不通过，且不执行成功任务提取。语义蕴含仍依赖原 Critic：现有字面核验并不等于证明任意结论在语义上正确。

## Extraction / Update

`MemoryExtractor` 使用保守、可测试的规则，不额外调用 LLM。只有存在有效 Plan、Final Result 且 Critic passed 的任务，才会提取已完成任务与结果摘要。输入使用原始 Query / Goal、实际 Plan 和最终结果，Critic Result 用作成功门槛，不把中间反馈原样当知识。

偏好只识别明确自述，例如“我喜欢简短的中文要点”“以后请详细回答”。重要事实仅采纳明确记忆指令，不根据视频台词推测用户身份。正文最多 1200 字，单次最多六条候选，不保存整段历史或完整报告。明显凭据文本不进入记忆。

原反馈接口继续保存 Checkpoint 反馈，同时走独立反馈提取路径；用户反馈是用户显式意见，不会因为存在反馈就把任务标成成功，也不会用反馈覆盖视频事实。

更新流程：

1. 规范化内容（空白、大小写）计算 SHA-256；同一 user / scope / type / 内容使用确定性 memoryId 和唯一键去重。
2. 重复观察合并 importance，更新最近来源和 updatedAt，保留 createdAt。
3. 已知偏好槽位（output.length / language / format）以及同一逻辑任务的摘要使用稳定 semanticKey；新内容把旧内容标记 SUPERSEDED。
4. `/analysis/memory/{memoryId}` 的 DELETE 只允许当前用户禁用自己的记录，写入 DISABLED。相同内容的任务重放不会恢复仍保留的禁用墓碑。
5. 按用户事务锁定现有 users 行，避免多实例并发产生重复活动记忆或突破容量约束。
6. 默认最多 200 条 ACTIVE，再保留最多三倍数量的非活动记录；淘汰低 importance、较旧记录。过量旧状态物理清理，限制总增长。超出保留窗口的禁用墓碑也会被清理。
7. 索引 upsert 使用相同确定性 ID；按该用户的 ACTIVE IDs 清理被替换/淘汰的 Qdrant 点，不影响其他用户。故障遗留旧点仍被 MySQL 状态复核过滤，后续成功更新时重试清理。

## Fail-open 与缓存

Gate、检索、embedding、Qdrant、提取、MySQL merge 和索引清理分别捕获异常；AgentLoop 外层还有增强边界。日志只记录阶段与异常类型，不输出 Query、Memory 内容或服务端响应正文。原来的视频检索、Planner、Executor、Critic 和 Checkpoint 的错误处理继续生效。

Memory embedding / Qdrant 单次 I/O 默认总超时 1500ms，SQL 查询超时 2s，更新事务超时 3s。这些是每次操作的限制，不是整段 Memory 流程的统一截止时间；批量写入仍会增加响应延迟。

开启 Memory 时，不再跨媒体内容复用最终分析结果，也不直接复用已完成的个性化结果；未完成的 Executor / Critic Checkpoint 仍可恢复。计划中的 `memoryAugmented` 只是个性化来源标记，默认 false，旧 JSON 兼容。关闭 Memory 后也会拒绝复用历史个性化结果/计划，重新生成普通结果，避免旧记忆输出跨用户传播。媒体内容级 ASR/OCR 复用和原视频检索保留。

## 开启与关闭

默认关闭。`.env.example` 和 `application.properties` 提供：

```dotenv
MEMORY_ENABLED=false
MEMORY_QDRANT_COLLECTION=agent_memories
MEMORY_TOP_K=5
MEMORY_MAX_PER_USER=200
MEMORY_IO_TIMEOUT_MS=1500
```

在原 `.env` 中设置 `MEMORY_ENABLED=true`，按原方式 `set -a; source .env; set +a` 导出环境，再启动后端。也可用 Spring 参数 `--memory.enabled=true`。切换需要重新启动后端；没有新增动态配置中间件。Qdrant 地址与 API key 复用现有 QDRANT_URL / QDRANT_API_KEY，collection 单独配置。

关闭后不访问 Memory collection、不执行 MemoryExtractor、不读写 Memory 表的业务记录，恢复普通结果复用规则（历史个性化结果除外）。Flyway 的建表/校验仍是正常应用启动迁移，不代表 Agent 在读写 Memory。

## 测试与验证边界

2026-10-15（Asia/Shanghai）实际结果：修改前原有 4 项测试通过；最终 `./mvnw -B -ntp package` 于 18:51 完成，29 项测试全部通过并生成可运行 JAR；真实隔离 MySQL 5.7.24 + Qdrant 1.18.2 联合运行于 18:52 完成，15 项全部通过（13 项 MemoryFlowTest 在真实 MySQL 上重跑，加 2 项 Qdrant 集成测试）。`git diff --check` 通过。临时服务已停止，业务数据库没有应用本轮测试数据；部署配置中的 MySQL 8.0 和完整后端启动未在本轮在线验证。

保持项目 JDK 21；服务器默认是 JDK 11，本轮在忽略目录 `server/target/tools` 准备 Temurin JDK 21，没有降低 pom 的 Java 版本。使用项目现有 Maven Wrapper、JUnit 5、Mockito，H2 仅为测试依赖。

- `MemoryFlowTest`：跨 Session / 视频的偏好与历史任务召回、Gate 跳过、SQL scope / 用户隔离、hash 复核、综合排名、重复合并、偏好替换、禁用、容量限制、并发去重、反馈、失败任务不提取和异常降级。默认用 H2；可指定隔离 MySQL 测试库运行同一组测试。
- `AgentLoopMemoryTest`：真实 AgentLoop 的 Qdrant 故障继续执行、历史引文无法作为当前视频证据、开关恢复行为、个性化缓存刷新、Executor Checkpoint 恢复、Prompt 分区与冲突策略。模型调用使用可控测试替身。
- `MemoryVectorStoreTest`：本机真实 HTTP 请求校验 collection 创建、用户/scope filter、payload、清理过滤器和 503 故障。
- `MemoryQdrantIT`：显式运行，使用隔离 Qdrant 1.18.2 验证实际 collection 与过滤/删除 API，并联合真实 SQL 验证跨 Session 记忆读写。Embedding 使用固定测试向量。

```bash
cd /path/to/DOVideo-AI/server
export JAVA_HOME="$PWD/target/tools/jdk-21.0.12.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp test

# 仅对可销毁的独立测试数据库 / Qdrant 使用；测试会清空该库中的测试表。
./mvnw -B -ntp -Dtest=MemoryFlowTest,MemoryQdrantIT \
  '-Dmemory.test.mysql.url=jdbc:mysql://127.0.0.1:13316/dovideo_memory_test?useSSL=false&characterEncoding=utf-8&serverTimezone=UTC' \
  -Dmemory.test.qdrant.url=http://127.0.0.1:16333 test
```

当前限制：规则不是通用语义记忆提取器，未覆盖的隐式偏好/改写不强行推断；任意事实的矛盾合并仍需更精确的键或用户禁用。Gate 有规则误判。未建立真实 Session ID 或长期用户目标管理。SQL 成功但 embedding 失败的记录暂时可能无法进行语义召回；历史任务/偏好可降级召回，再观察相同记忆时重试索引，没有后台补索引任务。启用记忆会减少最终结果缓存复用并增加有限 I/O 成本。后续真实本地模型验收结果如下，尚未全部通过。

## 2026-10-15 真实业务验收：部分通过

使用实际 HTTP 注册/登录、上传、RocketMQ、AgentLoop、MySQL、Redis、MinIO 和独立 Qdrant collection。LLM 为服务器 Ollama 的 `qwen2.5:7b-instruct`（独立临时 CPU 别名），Embedding 为 `qwen3-embedding:0.6b` CPU，1024 维。真实 faster-whisper tiny.en 和 Tesseract 处理两段 12 秒视频：A 为三个蓝色正方形，B 为五个红色正方形。7B 验收复用了前一轮真实预处理产生的 VideoContext；没有注入虚构 ASR/OCR 或模型回答。

| 业务请求 | 响应耗时 | 实际 LLM HTTP 次数 | 结果 |
| --- | ---: | ---: | --- |
| 首次分析 A，并表达简短中文要点偏好 | 248.419 秒 | 5 | Critic 通过，回答三个蓝色正方形 |
| 注销再登录，同用户“按照上次的方式继续” | 300.385 秒 | 4 | 偏好召回，最终回答超时；立即续问时未召回 EPISODIC_TASK |
| 普通视频事实查询 B | 209.551 秒 | 3 | Critic 通过，回答五个红色正方形；Memory 检索 0 次 |
| 错误历史记忆与 B 当前证据冲突 | 301.357 秒 | 2 | 错误记忆实际进入 Planner，但回答超时，不能判定冲突策略 E2E 通过 |

次数按透明代理最终完成的真实 HTTP 记录统计；超时取消后，上游仍可能完成一次请求，因此与业务超时瞬间的计数不同。预热、Embedding 不计入表中 LLM 次数。

隔离用户 5 最终 MySQL 有 8 条 ACTIVE 记录：3 USER_PREFERENCE、2 EPISODIC_TASK、2 PREVIOUS_RESULT、1 手工构造的冲突 IMPORTANT_FACT。前 7 条由实际成功业务提取，最后 1 条明确标记为测试夹具。第一次成功任务的五条自然记忆已全部入库、入 Qdrant；B 的两条自然记忆写入 SQL 后 Embedding/索引更新超时，业务仍成功。Qdrant 中用户 5 实际 6 个点：A 的 5 条记忆及冲突夹具。查询过滤 userId，不会召回同一隔离 collection 内其他测试用户的数据。

此次发现并修复：

- 续问直接返回 Markdown，丢失 Critic 未通过提示：改用现有 `TaskStatus.completed` 统一呈现结果。
- OCR 将 stderr 的分辨率诊断混入证据：识别 stdout 与诊断 stderr 分开处理。
- 完成结果早于记忆更新发布：先尝试 Memory Update，再保存最终结果；已有 Critic checkpoint 支持中断恢复。Memory 更新失败仍发布正常结果；刷新中不把旧结果提前显示为完成。

修复后 JDK 21 Maven 回归 39 项全部通过，打包成功，实际启动健康检查返回 UP。四案例完整 E2E 发生在完成时序修复之前；没有把修复后的回归测试和健康检查称为完整 E2E 重跑。早期 1.5B 模型虽快，但生成错误数量、颜色和时间戳，Critic 全部拒绝，未写入自然长期记忆。

验收证据保存在 `server/target/memory-e2e-20261015/`：`e2e-results.json`、`final-memory-db.json`、`actual-memory-db.sql`、`final-qdrant-points.json`、`model-calls.jsonl`、`qdrant-calls.jsonl`、`asr-calls.jsonl`、`key-application.log`、`completion-race-tests.log`、`deployment-verification.json` 和脱敏压缩包。私有连接凭证不包含在交付包中。临时运行配置没有替换项目默认配置。

验收结论：本地千问可以接入真实业务，但当前 GPU 显存不足，7B CPU 路径无法稳定在 300 秒内完成含记忆的续问与冲突核验；项目默认预算仍为 120 秒。跨会话 EPISODIC_TASK 的立即续问及冲突回答优先证据，不能据本次结果宣称已通过。短视频夹具也不代表长视频规模验证。

</details>
