# 公开发布范围

本仓库是当前修改版工作区的源码快照，包含当时未提交的功能改动，保留上游 MIT 许可证和来源说明。原开发目录和运行服务不因公开发布而删除或重置。

保留内容：

- `server/src`：业务实现、单元测试、显式集成测试、V1–V5 migration。
- `client/src`：新版手绘工作台、交互、组件和主题。
- Maven Wrapper、`pom.xml`、`package.json`、锁文件和 CI 模板。
- Docker Compose、RocketMQ Broker 配置、启动脚本。
- 使用占位值的 `.env.example`、设计文档、新版 UI 截图和脱敏性能样本。

未发布内容：

- `.env`、服务连接密码、API Token、SSH 密钥和账号凭据。
- MySQL / Redis / Qdrant / MinIO 数据目录、数据库备份、用户视频。
- 模型调用原文、Memory DB 内容、会话文件、运行日志和临时 E2E 证据。
- `target`、`node_modules`、`dist`、Python 虚拟环境、模型权重与缓存。
- 原服务器 Git 历史和 remote 配置；用新的 Git 历史初始化，避免历史文件一并公开。
- 上游旧 UI 截图；本版本改为当前前端实际运行的演示模式截图。

公开内容中的测试密码/Token 字符串仅为源码夹具或明确占位值，不能用于连接实际服务。公开仓库不包含可直接访问开发服务器的配置。

开发者仍应在每次提交前检查 `git status` 和 `git diff --cached`；`.gitignore` 不是秘密扫描工具，也不会自动撤销已经提交的敏感信息。初次运行时需自行创建本地配置、准备模型与 ASR 服务。

本次发布前在隔离源码副本上实际检查：JDK 21 Maven 默认测试 83 项通过、0 失败/错误/跳过；前端 2 项测试通过且生产构建成功；启动脚本语法与 Compose 模板校验通过。另检查常见密钥格式、开发服务器地址、Markdown 链接，并在服务器本地比对真实配置中的 5 类非占位凭据，未发现匹配；真实配置原文没有导出到发布副本。

默认 Maven 测试包含 Mock/H2，不能据此宣称真实外部服务或视频 E2E。README 的性能数值来自单独实测，其条件和限制另见性能文档。

为使用本次已有 GitHub 授权范围完成发布，自动化配置保存在 `.github/ci-template.yml`，不会自动触发 Actions。需要启用时，可将其移到 `.github/workflows/ci.yml` 后提交；通过 CLI 推送 workflow 还需 GitHub 授予对应权限。
