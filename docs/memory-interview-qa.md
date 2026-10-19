# Memory V5 源码问答（2026-10-18）

以下回答对应当前源码；下方旧版内容保留作历史记录，不再代表当前更新顺序或能力边界。

### 1. 为什么不直接把聊天历史塞进向量库？
原始聊天混有临时请求、错误和引用。MemoryExtractionService 只处理可信用户文字与通过 Critic 的结构化任务摘要；工作队列暂存载荷不是可检索长期知识。

### 2. LLM 提取是不是另一套模型服务？
不是。MemoryLlmExtractor / MemorySemanticResolver 都调用现有 DeepSeekUtils.memoryJson，复用 LangChain4j ChatModel 和配置。独立超时、单次尝试和 optional semaphore 防止记忆请求堆积占满主调用池。

### 3. 为什么不能直接保存模型给出的 content 和 memoryId？
模型会改写否定、条件，甚至编造 UUID。提取只保存已定位的用户 quote；已知语义槽位由程序规范化。关系模型只选择有界候选序号，由服务器映射 memoryId，事务再复核 owner/scope/status/hash。

### 4. 否定和条件如何处理？
原文、condition、correction 和 provenance 都持久化。程序检查 quote 没有漏掉原句的否定或时间限定；条件使用独立槽位，不能覆盖全局偏好。规则降级也保留原文，不把“不喜欢详细”变成“喜欢详细”。

### 5. 如何避免把视频台词当用户指令？
提取输入不含 ASR/OCR。程序进一步拒绝用户请求中的转述、引用和假设，即使模型只截取引号里的“我喜欢”也拒绝；Memory 永远不是视频证据。

### 6. UPDATE、CONTRADICTION、UNCERTAIN 怎样落库？
可靠固定属性规则保留；一般语义覆盖必须有明确修正意图和受信任来源。UNCERTAIN 不自动退休旧知识。SQL 锁定所属用户并验证关系目标版本，不把模型判断当数据库授权。

### 7. 为什么旧任务重放不会复活旧偏好？
精确内容有稳定 ID，DISABLED/SUPERSEDED/COMPRESSED 状态不重新 ACTIVE；还检查同一 sourceTaskId/属性的退役记录。队列 ID 和完成回执抑制重复工作。保守代价是不能仅重放旧文本来恢复退休的原文。

### 8. 压缩真的做了什么？
同用户、同任务主题、同类型和兼容范围的旧记录合成层级摘要；相同 Plan 公共部分只保留一次，各次任务目标、产物和来源仍保留。程序验证可重建的源文本；不能安全合并就放弃。它是保守抽取式整合，不是任意 LLM 自由摘要。

### 9. 压缩中途失败会不会丢记忆？
摘要、来源边、源记录 COMPRESSED 状态和索引 outbox 处于同一 MySQL 事务。失败回滚。用户行锁、来源版本复核和确定性摘要 ID 保护并发，原文不删除。

### 10. MySQL 与 Qdrant 是否强一致？
不是。MySQL 是权威，事务 outbox 最终补偿 Qdrant。召回再次检查用户、范围、状态和内容 hash；重建窗口可能漏召回，但过期索引不能变成授权知识。

### 11. 为什么先保存最终结果再更新 Memory？
任务成功不能依赖可选 LLM。AgentLoop 先保存最终 Checkpoint，再排队。后台租约、退避与 DEAD 重试管理失败；补偿扫描修复保存结果后崩溃的窗口，只恢复已验证任务/结果，不从上下文猜用户偏好。

### 12. Memory 开关怎样影响缓存和用户修订？
Gate 跳过、失败或零召回返回 null，普通缓存不因总开关开启就失效。只有实际 Memory 才有 memoryAugmented；correctedTasks 的 userRevised 标记优先，不让个性化策略重写用户任务。

### 13. Memory 与 Checkpoint、Evidence 的区别？
Checkpoint 是工作流恢复；Memory 是用户知识；Evidence 是当前视频中可核验的 ASR/OCR。恢复任务从 Checkpoint 派生事件不等于把整个 Checkpoint 当记忆搜索。当前任务覆盖旧偏好，视频事实冲突以当前 Evidence 为准。

### 14. 如何证明实现而不夸大测试？
默认 Maven 测试覆盖 Mock 模型、H2 事务、真实 HTTP 故障和实际编排。显式 MemoryLiveIT 使用真实本地 Qwen/Embedding/MySQL/Qdrant，但任务结果是结构化样本，所以只能称 Memory 基础设施集成，不是完整视频 E2E。上传→ASR→OCR→Planner→Critic 需要另行真实验收。

### 15. 还有哪些值得主动讲的限制？
后台更新存在短暂可见性延迟；没有真正业务 sessionId；压缩主题采用实际 Plan hash，较保守；规则和 quote 检查会漏提或误拒；原文墓碑、来源链和队列回执需要存储治理；模型关系不是数学事实证明，未知冲突保守保留。

详细配置、调用链、测试命令与复现案例见 [memory-design.md](memory-design.md)。

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

# Memory 实现问答

### Conversation History 为什么不是 Long-term Memory？

历史聊天保留原始过程，其中有无效尝试、临时指令和中间错误。长期记忆需要筛选价值、确定用户归属、注明来源、控制容量并能被替换。本项目只提取明确偏好、用户完成的任务、简短结果、显式事实和反馈，不保存完整往返记录。

### Checkpoint 为什么不是 Memory？

Checkpoint 服务于同一任务的恢复，保存 Plan、Executor 草稿、Critic 等工作状态。Memory 服务于跨任务/会话的知识复用。项目保留原 Checkpoint 表和恢复逻辑，新增独立 Memory 表/向量 collection，不把 Checkpoint 当知识库搜索。

### Working / Episodic / User Preference 有什么区别？

Working Memory 是当前执行上下文，生命周期跟任务走；EPISODIC_TASK 是“以前完成过什么任务”的简短记录；USER_PREFERENCE 是用户明确表达且可跨任务复用的偏好。前者不直接导入长期记忆，后两者经过筛选进入持久化存储。

### Goal Context 是否新增复杂目标系统？

没有。原 `VideoContext.userGoal` 继续表示当前分析目标。轻量 GoalContext 额外区分长期用户目标、会话目标和当前任务目标；没有可靠来源的字段保持 null，不由模型猜。追问 Gate 读取原始 question，不读取自动包装的历史文本。

### 为什么要 Memory Gate？

普通视频事实查询通常只需要当前证据。Gate 用轻量历史依赖规则决定是否查记忆，避免每次都访问 DB、生成 embedding 和查询 Qdrant。它不调用 LLM，异常时跳过记忆。

### Retrieval 是否只按向量相似度排序？

不是。候选经过用户、scope、ACTIVE 状态和内容版本复核，再用 similarity、importance、recency 综合打分取 Top-K。明确“上次继续”的请求可以加入最近完成的任务，习惯类请求可以加入用户偏好；向量不可用时这些类型仍可从权威 SQL 降级召回。

### 怎样避免不同用户的 Memory 混在一起？

身份来自媒体所有者和现有鉴权，不信任客户端自报或模型输出。Qdrant filter 和所有 SQL 都按 userId 限定；服务层再次检查 owner / scope / status / contentHash。GLOBAL_USER 也只是“当前用户的全局”，不是所有用户共享。禁用和索引清理也带用户条件。

### 为什么 MySQL 和 Qdrant 都需要？

MySQL 管理原文、来源、时间、状态、去重和事务；Qdrant 只负责向量候选。独立 Memory collection 与 Video Evidence collection 不共用。向量结果必须回读 MySQL，旧的或错误的索引点不能绕过业务状态。

### Update 怎样实现去重、合并和替换？

规范化内容 hash 与确定性 ID 负责基础重复检测；MySQL 唯一键与用户行事务锁处理并发。重复观察刷新最近来源和 importance；稳定偏好槽位的新值将旧值 SUPERSEDED。同一任务的结果摘要也可更新。用户可禁用自己的记忆；ACTIVE 和旧状态记录均有容量限制。

### 为什么不额外用 LLM 提取记忆？

本轮先用保守规则打通真实持久化与 AgentLoop，避免推测用户画像和额外模型成本。任务记忆必须由通过 Critic 的实际结果产生，偏好与事实必须有明确用户表述。规则覆盖范围有限，是当前已说明的限制，而不是空壳 LLM 模块。

### Memory 和 Video Evidence 冲突怎么办？

Prompt 明确区分 Current Task、Agent Memory、Video Evidence，并要求当前视频证据优先。记忆不能作为视频事实的 ASR/OCR 原文或时间戳证据。原代码层证据校验继续生效，无法从当前视频核验的历史引文会使 Critic 结果降为不通过。任意结论的语义蕴含仍需要模型 Critic 判断，不把字面校验夸大成完整事实证明。

### Memory 系统故障会让视频 Agent 失败吗？

新增能力采用 fail-open：Gate、embedding、Qdrant、SQL 读写、提取和清理各自捕获异常，AgentLoop 增强入口再兜底。没有可用记忆时继续原视频检索和 Planner / Executor / Critic。可选网络操作有短超时，不占用原视频执行预算；原主链自身依赖失败仍由原逻辑处理。

### 关闭开关后是否仍会访问 Memory？

`MEMORY_ENABLED=false` 时不查询 Memory collection，不做 Memory Extraction，也不读写 Memory 业务记录。旧的个性化计划带来源标记，关闭后不拿它们冒充普通缓存。原视频检索、ASR/OCR 复用、普通 Checkpoint 和模式能力保留。

### 如何证明已经接入，而不是只有设计？

分析与追问入口都构造 MemoryRequest，AgentLoop 在视频检索前召回、在通过 Critic 后更新，原反馈 API 也接入 Memory 更新。JUnit 覆盖真实编排与关闭行为；SQL 测试既可用 H2，也可用真实隔离 MySQL；显式集成测试使用真实 Qdrant API。模型/embedding 使用测试替身的范围和未进行在线视频验收的限制都记录在设计文档。

具体数据流、配置、文件职责、运行命令及限制见 [memory-design.md](memory-design.md)。
# 真实验收补充（2026-10-15）

**能否用本地千问，不使用云端 API token？**

可以。当前调用客户端支持 OpenAI 兼容接口；真实验收使用服务器已有 Ollama 的 `qwen2.5:7b-instruct` 和 `qwen3-embedding:0.6b`，语音识别单独使用真实 faster-whisper 服务。客户端的 API key 占位值不代表云端凭证。LLM、Embedding、ASR 必须分别可用，聊天模型本身不会替代全部服务。

**为何数据库最后有 EPISODIC_TASK，却不能说“按照上次继续”验收通过？**

实际发现前端完成结果发布早于逐条记忆更新。用户立即注销重登并续问时，偏好已写入，而任务记忆尚未写入，因此那次真实 Planner 只收到了偏好。最终数据库有记录不能证明更早那次请求召回了它。已修复发布顺序并增加 checkpoint 恢复/状态回归测试；完整修复后 E2E 尚未重跑。

**Prompt 声明视频证据优先，是否等于真实冲突验收成功？**

不等于。必须检查实际召回、最终回答及 Critic。此次错误记忆确实进入真实 Planner，但 7B CPU 推理超过 300 秒，没有最终回答，故该项未通过；更早 1.5B 还生成了冲突错误答案，被 Critic 拒绝。回归测试验证策略与提示，不替代真实模型业务结果。

**Memory fail-open 在实际业务中观察到了什么？**

视频 B 正常分析回答五个红色正方形并通过 Critic；后续两条 Memory 已写 MySQL，而 Embedding/索引更新超时，日志记录 `agent_memory_fail_open stage=embedding_or_index_update`，主业务仍成功。这也揭示 SQL 成功而向量暂缺的限制，不能声称两个存储始终同步。

</details>
