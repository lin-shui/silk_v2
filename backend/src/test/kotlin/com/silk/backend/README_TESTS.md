# Backend Tests

当前快检覆盖：

- `BackendHttpContractTest`：注册、登录、用户设置、群组创建/加入、消息撤回、Todo HTTP 合同。
- `BackendFileContractTest`：文件上传、重名处理、列表、下载、删除合同，上传后 `FILE` 消息 payload 的实时广播/历史回放，以及 `processed_urls.txt` 本地路径分支。
- `BackendWebSocketContractTest`：群组 WebSocket 入群鉴权、历史回放、广播持久化、未读计数，以及本地 URL/PDF 入口成功分支的文件消息广播/历史回放、404/非支持内容类型、读超时、连接拒绝、损坏 PDF 等失败分支的下载去重、状态回显与落盘 smoke。
- `BackendPersistenceContractTest`：旧 SQLite 初始化不丢登录/设置/群组数据，聊天历史重启恢复元数据、追加消息不覆盖旧记录，坏历史文件拒绝覆盖并保留备份。
- `ai/DirectModelAgentToolPolicyTest`：AI 工具暴露面、会话作用域、路径拒绝与审计结果。
- `ai/DirectModelAgentAutoCliTest`：AutoCLI 工具启用条件、命令注入拒绝、站点白名单与可选真实执行。
- `ai/DirectModelAgentCitationTest`：搜索/AutoCLI 证据引用标记、垃圾结果过滤、引用校验和最终引用重编号。
- `utils/WebPageDownloaderSmokeTest`：URL 提取去重、本地 HTML/PDF 下载提取与落盘 smoke，不依赖外网。
- `UserTodoStoreTest`：待办去重、重开、模板实例化等核心生命周期逻辑。
- `trust/TrustedDirManagerTest`：目录信任的用户/bridge 隔离、子目录继承、幂等、删除与持久化。
- `agents/core/AgentRuntimeTest`：agent 激活、消息路由、取消、错误回显与基础状态机。
- `agents/core/AgentRuntimeAcpIntegrationTest`：`AgentRuntime` 与 ACP client 的会话启动、流式更新和 resume 集成。
- `agents/core/AgentSessionTest`：agent session 状态与生命周期。
- `agents/core/CommandRouterTest`：`/use`、`/codex`、`@agent` 等 agent 指令解析。
- `agents/core/GroupAgentContextTest`：群组 agent 上下文、active agent 与 per-agent session 持久化。
- `agents/core/AcpUpdateMapperTest`：ACP `session/update` 到 Silk 消息的映射。
- `agents/acp/AcpClientTest`：ACP JSON-RPC client 行为。
- `agents/acp/AcpRegistryTest`：ACP adapter 注册、断连与查找。
- `kb/KnowledgeBaseRouteContractTest`：KB 路由合同（ACL、JWT、capture、memory CRUD、context preferences）。
- `kb/KnowledgeBaseMemoryTest`：记忆管理全链路（显式/自动记忆、敏感过滤、去重合并、TTL 衰减、群组记忆）。
- `kb/KnowledgeBasePromptContextTest`：prompt 注入优先级（manual > pinned > auto > memory）、排除/空间过滤、诊断计数。
- `kb/KnowledgeBaseAiActionsTest`：AI action 解析与执行（create/update entry、workflow provenance）。
- `kb/KnowledgeBaseCopilotTest`：KB Copilot 草稿生成与写回。
- `kb/KnowledgeBaseManagerAccessControlTest`：权限控制测试。

运行方式：

```bash
./gradlew :backend:test
```

新增测试时保持两点：

- 优先写能直接拦截回归的真实接口/逻辑测试，避免字符串占位测试。
- 使用 `TestWorkspace` 隔离 SQLite 与 `chat_history`，不要把测试产物写回仓库根目录。
