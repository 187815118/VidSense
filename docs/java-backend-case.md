# VidSense：英文 Java 后端真实视频案例

本案例使用已部署的真实服务、合法独立测试账号和本地模型，不使用 Mock。执行日期：2026-10-19。截图中的账号仅用于本案例，没有展示其他用户数据。

## 视频、模型与截图

来源：[Java Brains — Spring Boot Quick Start 11 — Adding a REST Controller](https://www.youtube.com/watch?v=oRFCeRVWCNE)。原视频约 475.6 秒。当前截图播放的是原视频 03:00–04:59.8（119.8 秒）的真实剪辑，播放器与回答中的时间戳均相对剪辑起点，应加 180 秒才能对应原视频。

本地模型：Qwen2.5 7B Instruct、Qwen3 Embedding 0.6B、faster-whisper tiny.en；视频实际经过 ASR、OCR、VideoContext、Agent 与 Critic。没有降低证据核验标准或提高生产默认 120 秒 Agent 时限。Memory 使用原有规则提取；本次没有启用可选的 LLM 提取、语义整合或压缩来冒充其真实验收。

截图通过服务端浏览器登录实际页面生成，无 `?demo`。视频 readyState=4，实际可播放；无浏览器 pageerror。只公开截图、来源链接与脱敏指标，不发布原视频、音轨、完整转写、账号凭据、Session 或模型调用日志。

## 实际执行结果

| 尝试 | 后台/追问耗时 | 实际结果 |
|---|---:|---|
| 完整原视频首次分析 | 139.906 s | 失败：ASR 后 Agent 达到 120 秒期限 |
| 完整视频从 Checkpoint 继续 | 81.847 s | 完成但 Critic 未通过，未作为成功任务写记忆 |
| 原视频 04:00–06:00 片段 | 30.333 s | Critic 通过；3 次 LLM；3 次 ASR 中有 1 个尾段失败，不能称转写全部成功 |
| 该成功片段重新登录追问 | 55.275 s | Critic 通过；5 次 LLM；Planner 实际收到 5 条 Memory |
| 在该片段收窄目标再分析 | 46.414 s | 完成但 Critic 未通过 |
| 当前截图：03:00–04:59.8 | 46.431 s | 完成但 Critic 未通过；5 次 LLM，2 次 ASR、1 次 OCR |

当前截图对应任务提交返回 HTTP 202，服务端本机调用耗时 **15.101 ms**；上传不计入这个数字。后台 46.431 s 从提交开始计时，包含轮询观测误差。阶段指标可重叠，不应将子阶段耗时再累计到总耗时。它是单次真实观测，不能推导平均值、P95、并发能力或相对同步版本的提升比例。

当前任务目标：

> 我喜欢简短的中文要点。只说明当前片段中的 RestController 注解和控制器方法两个要点。用连续英语原文和时间戳作为证据；不要补充片段外知识。

实际回答提出 RestController 来源及控制器方法映射两个要点，但 Critic 报告 00:30 的引用未能在原始 ASR/OCR 中核验，并要求补充作用与映射说明。证据支持率与结论证据支持率均为 0.5。前端明确保留警告，没有将其伪装为全通过。

## 实际 Memory 与继续追问

此前通过 Critic 的 04:00–06:00 片段实际写入 5 条 Memory：3 条 USER_PREFERENCE（简洁、中文、要点）、1 条 EPISODIC_TASK、1 条 PREVIOUS_RESULT。异步写入完成后登出再登录，提问“按照上次的方式继续……”，在真实模型调用记录的 Planner Prompt 中确认了这 5 条记忆，包含 EPISODIC_TASK。

重新登录验证了认证会话变化后的读取与实际 Planner 集成；业务路径未提供可信的持久化 sessionId，所以不宣称已经完成严格的多业务 Session 状态管理。当前截图的跨视频任务摘要来自此前通过的任务，不能充当当前视频的证据。

人工复核发现，早先通过 Critic 的回答把课程中的 GET 演示推广成了 RequestMapping 的默认规则。按照 [Spring 官方说明](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)，未指定 method 的 RequestMapping 不限于 GET。已通过现有反馈接口保存纠正，并通过有归属校验的禁用接口禁用 1 条错误 PREVIOUS_RESULT，保留墓碑；没有直接改 SQL 掩盖记录。

这也说明：Critic 的源一致性检查不是外部知识真值保证。历史任务摘要仅说明执行过什么，不应当被提升为视频事实或可信技术知识。

## 展示接口与可复现步骤

新增 `MemoryReadController` 的 `GET /analysis/memory?videoId=<当前视频ID>&limit=20`：从登录身份取 userId，先校验视频归属，再读取适用的 ACTIVE 记录；只读 MySQL，不触发 Memory 检索、Embedding 或 LLM。未登录、关闭、失败、空记录与演示模式分开显示。前端切换账号时清空状态并丢弃过期请求，防止上个账号的列表残留。

1. 启动 README 中的依赖、真实模型、ASR 和前后端，开启 Memory；使用英语适配的 ASR。
2. 用有权访问的视频素材上传实际短片，并输入上面的目标；记录提交 HTTP 状态与耗时，轮询至终态。
3. 检查真实 Agent Trace / Evaluation / Critic，不能只看 COMPLETED。
4. 仅在 Critic 通过的任务后等待异步 Memory 写入，检查上述三类记录。
5. 登出再登录，用“按照上次的方式继续”追问，核对检索轨迹及 Planner 收到的 Memory；不能仅凭 UI 卡片推断发生了召回。
6. 刷新已保存记忆并展开来源；若人工发现错误，通过反馈和禁用接口处理，不修改成功标志或降低核验要求。

同一视频同一目标可能命中 Checkpoint，不能把缓存结果计为新的冷启动分析。模型输出具有波动，复现不保证每次都通过；本次失败尝试也列在上表。

## 验证与边界

JDK 21 Maven 默认测试 87 项通过，前端 5 项测试通过，前后端构建成功。新增 4 个 Memory 只读 API 测试和 3 个前端读取状态测试。单元测试含 Mock/H2，与本案例的真实外部服务执行分开统计。

测量数据见 [java-backend-case.json](benchmarks/java-backend-case.json)。其中保留每次耗时、Critic 状态、模型调用计数、阶段指标、人工纠正和截图状态；不包含可复用认证信息。当前模型/转写和证据引用可靠性仍有限，完整长视频未通过本轮验收，不能以短片成功替代完整长视频成功。
