# Test Matrix

## Backend Route / Contract

- Command: `./gradlew :backend:test`
- Primary tests:
  - `BackendHttpContractTest`
  - `BackendFileContractTest`
  - `BackendWebSocketContractTest`
  - `BackendPersistenceContractTest`

## AI / Search / URL Download

- Command: `./gradlew :backend:test`
- Primary tests:
  - `ai/DirectModelAgentToolPolicyTest`
  - `ai/DirectModelAgentAutoCliTest`
  - `ai/DirectModelAgentCitationTest`
  - `utils/WebPageDownloaderSmokeTest`

## Todo Lifecycle

- Command: `./gradlew :backend:test`
- Primary tests:
  - `todos/UserTodoStoreTest`

## Agent Framework / ACP

- Command: `./gradlew :backend:test`
- Primary tests:
  - `agents/core/AgentRuntimeTest` — 包含 Room TEAM `/new` 在 Silk 内重置会话、不转发给外部 CLI 的回归
  - `agents/core/AgentRuntimeAcpIntegrationTest`
  - `agents/core/AgentSessionTest`
  - `agents/core/CommandRouterTest`
  - `agents/core/GroupAgentContextTest`
  - `agents/core/AcpUpdateMapperTest`
  - `agents/acp/AcpClientTest`
  - `agents/acp/AcpRegistryTest`

## External Agent Device Authentication

- Command: `./gradlew :backend:test`
- Primary tests:
  - `AgentAuthRouteContractTest` — 目标 loginName 预绑定、fragment 配对链接、跨账号短码 not-found、JWT 配对审批、设备 proof、已登记设备签名新增 Agent、requestId/账号隔离、未绑定初始状态、单设备 WSS 双 Agent ACP 初始化/Host 替换、兼容 Bridge 连接、Agent/设备分级撤销
  - `DeviceManagementModelsTest` — 配对短码格式化、`#code=` fragment 解析与无效 fragment 拒绝，以及 Binding 展示/权限纯逻辑
  - `AgentPairingOriginTest` — 未配置时使用 Host `--server` 而不是监听地址、canonical 配置覆盖连接别名、Web 分端口显式 origin 与非法 origin 拒绝
  - `AgentBindingApprovalRouteContractTest` — 跨所有者 Binding 双向发起、双方审批、更新后重新审批、目标管理者撤销、拒绝后重新申请
  - `AgentBindingTeamRoutingContractTest` — Room TEAM ACTIVE Binding 从聊天广播到精确设备签名 Agent 的 ACP prompt 主链、TEAM `session/new` 不携带 Backend/其他设备 cwd，以及定向 `/new` 不进入 ACP vendor prompt
  - `agents/auth/AgentBindingAuthorizationTest` — 设备签名连接精确绑定 `agentInstanceId`、Binding 权限和声明能力交集
  - `agents/auth/AgentAuthProtocolTest` — raw Ed25519、公钥指纹、设备/新增 Agent canonical payload、origin 绑定、过期与一次性 challenge
  - `agents/auth/AgentAuthRateLimiterTest` / `AgentPairingRetentionTest` — 限速窗口、攻击者 key 清理、桶硬上限，以及过期配对 7 天保留边界
  - `agents/acp/AcpMultiplexedTransportTest` — 每 Agent 有界逻辑 transport 的双向隔离、幂等关闭与 Host 关闭不回显
  - `agents/core/AgentBindingTriggerMatcherTest` — Room TEAM 的 ALL/MENTION/EVENT 触发隔离、跨 Agent mention 过滤及定向 `/new` 提取
- Host command: `cd silk-agent && go test ./...`
- Host coverage: DeviceSigner 系统凭据优先级与 secure-file fallback、加密 backup/restore/rotate、canonical protocol（含签名新增 Agent）、HTTPS/WSS TLS、连接/认证 origin 分离、pairing/add-Agent HTTP credential boundary 与 redirect credential 防泄漏、受保护控制 socket/Windows pipe 与新 Agent 热加载、PID/config handling、日志增量 follow/截断恢复、单一 WSS `agentInstanceId` envelope/双 Agent 路由隔离、全启用身份撤销后的终止行为、Adapter stdin/stdout JSON-RPC v2 nonce binding/双向 ACP、Unix wrapper/随包 Python 模块 ownership/parent trust、Windows Adapter source owner/DACL 与 `CREATE_NO_WINDOW` 启动标志、credential/raw-I/O 环境过滤和 Python bytecode 抑制、health/shutdown lifecycle、当前用户级 systemd/LaunchAgent/Scheduled Task 模板与含空格 profile 路径持久化、Windows profile 目录 DACL 收紧/子文件继承、Windows 私密文件 owner/DACL、签名 release manifest/hash/版本/未签名文件与宽泛输出权限拒绝；竞态检查使用 `go test -race ./...`，六平台交叉编译使用 `CGO_ENABLED=0 GOOS=<linux|darwin|windows> GOARCH=<amd64|arm64> go test -c`
- Release smoke: `silk-agent/scripts/package-release.sh <version> <empty-output-dir> <0600-private-key-file> <public-key-file>` 构建并复验六个平台签名 bundle；GitHub tag/手动发布使用 `.github/workflows/release-silk-agent.yml`。
- Direct Bridge command: `python3 -m pytest bridge_common/tests cc_bridge/tests codex_bridge/tests -q`（先安装开发依赖 `python3 -m pip install pytest`）；`python3 -m py_compile bridge_common/*.py cc_bridge/acp_adapter.py cc_bridge/executor.py codex_bridge/codex_adapter.py codex_bridge/codex_executor.py`；覆盖 Host IPC v2 初始化、health/shutdown、双向 ACP 转发、权限信封 fail-closed 解析、跨设备无效 cwd 的纯消息回退与 Workspace fail-closed、Claude/Codex 继承设备原生认证/配置而仍由 CLI 参数限制本地文件/命令工具及 sandbox、Claude 丢失 resume session 后仅对明确 missing-session 错误自动新建、JSON/UTF-8 边界对合法 Unicode 与孤立 surrogate 的处理及两个 Adapter/Executor 的语法检查。
- CI: `.github/workflows/ci-fast-validation.yml` 的 `external-agent-auth` job 固定运行 Host test/race/vet/Windows compile 与 Direct Bridge IPC/syntax 检查；`external-agent-auth-windows` 在 Windows runner 实际执行私密文件 DACL、Scheduled Task action 和 profile 路径合同。

## Knowledge Base

- Command: `./gradlew :backend:test`
- Primary tests:
  - `kb/KnowledgeBaseRouteContractTest` — KB 路由合同（ACL、JWT、capture、memory CRUD、context preferences）
  - `kb/KnowledgeBaseMemoryTest` — 记忆管理全链路（显式/自动记忆、敏感过滤、去重合并、TTL 衰减、群组记忆）
  - `kb/KnowledgeBasePromptContextTest` — prompt 注入优先级（manual > pinned > auto > memory）、排除过滤、空间过滤、诊断计数
  - `kb/KnowledgeBaseAiActionsTest` — AI action 解析与执行（create/update entry、workflow provenance）
  - `kb/KnowledgeBaseCopilotTest` — KB Copilot 草稿生成与写回
  - `kb/KnowledgeBaseEmbeddingTest` — 嵌入向量缓存与混合搜索（cosineSimilarity、l2Normalize、KbEmbeddingCache CRUD、NoOpEmbeddingProvider、混合搜索集成）
  - `kb/KnowledgeBaseManagerAccessControlTest` — 权限控制测试

## Trusted Directory / Workflow Directory Trust

- Command: `./gradlew :backend:test`
- Primary tests:
  - `trust/TrustedDirManagerTest`

## Workflow Room / Personal Workspace

- Command: `./gradlew :backend:test`
- Primary tests:
  - `workspace/WorkspaceManagerTest` — workspace CRUD、runtime seed 与 legacy workflow migration
  - `workspace/WorkspaceAccessPolicyTest` — Owner/Co-pilot/Observer 权限、发送时可见性快照、历史 scope 持久化与 fail-closed 消息目标
  - `workspace/WorkspaceHistoryMetadataContractTest` — 已撤销共享历史的 Owner/名称展示、PRIVATE 重命名隔离与 runtime 元数据脱敏
  - `SilkAiTriggerPolicyTest` — `[Silk]` 私聊隐式触发，Team Channel 仅完整的前置 `@Silk` mention 触发且与成员数无关
  - `BackendHttpContractTest` — Workflow Room Owner 增删成员、非成员鉴权、成员发现 Room 与移除后 Co-pilot 清理
  - `BackendWebSocketContractTest` — 普通 Room WebSocket 回放/广播/成员校验，以及 Workflow Room 双用户、双连接、PRIVATE/SHARED、Observer/Co-pilot 动态授权与同用户多工作区隔离

## GitHub Integration

- Command: `./gradlew :backend:test`
- Primary tests:
  - `GitBindingRouteContractTest` / `GitWebhookRouteContractTest` — Workflow/Owner 权限、脱敏 binding 与 HMAC Webhook 合同
  - `git/GitIngestionModeResolverTest` — `AUTO|WEBHOOK|POLLING` 选择和公网 HTTPS 校验
  - `git/GitHubClientTest` — Hook reconcile、Issues/PR Polling、ETag、分页和额度响应头
  - `git/GitPollingServiceTest` — 静默 baseline、Issue/PR snapshot diff、pending 投递和限流退避
  - `git/GitEventStoreTest` — delivery 与 Webhook/Polling 跨来源语义去重

## Web File Contract / Parser

- Commands:
  - `./gradlew :frontend:webApp:nodeTest`
  - `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs`
- Primary test:
  - `frontend/webApp/src/test/kotlin/com/silk/web/FileContractsTest.kt`
  - `frontend/webApp/src/test/kotlin/com/silk/web/DeviceManagementModelsTest.kt` — 配对码规范化、Binding target/scope/权限映射与待审批状态文案
- `/device` 静态入口 smoke：生产构建后运行 `python3 scripts/silk_static_server.py 8015 --bind 127.0.0.1 --directory frontend/webApp/build/dist/js/productionExecutable`，确认 `GET /device` 返回 `200 text/html`

## Shared Room Contract

- Command: `./gradlew :frontend:shared:desktopTest`
- Primary test:
  - `frontend/shared/src/commonTest/kotlin/com/silk/shared/RoomModelsTest.kt`
- Note: shared `jsTest` requires a configured headless browser; use the desktop target for stable local/CI commonTest execution.

## Android File Contract / Parser

- Commands:
  - `./gradlew :frontend:androidApp:testDebugUnitTest`
  - `./gradlew :frontend:androidApp:compileDebugKotlin`
- Primary tests:
  - `frontend/androidApp/src/test/kotlin/com/silk/android/FileContractsTest.kt`
  - `frontend/androidApp/src/test/kotlin/com/silk/android/WorkflowPathUtilsTest.kt`（工作流目录浏览路径工具）

## Desktop File Contract / Parser

- Commands:
  - `./gradlew :frontend:desktopApp:test`
  - `./gradlew :frontend:desktopApp:compileKotlin`
- Primary test:
  - `frontend/desktopApp/src/test/kotlin/com/silk/desktop/FileContractsTest.kt`

## Shell / Ops Script

- Commands:
  - `bash -n silk.sh`
  - `./silk.sh status`
- CI supplement:
  - `.github/workflows/ci-script-smoke.yml` 覆盖 `./silk.sh build`、`./silk.sh build-apk`、`./silk.sh build-all` 与 `./silk.sh deploy` 编排 smoke
  - `.github/workflows/ci-script-smoke.yml` 另覆盖 `./silk.sh start` / `./silk.sh stop` 运行态 smoke（本地 Weaviate mock、后端 `/health`、前端静态服务）

## Kotlin / Script Lint

- Command: `./gradlew silkLint`
- Coverage:
  - detekt checks Kotlin source across `backend` and Gradle frontends
- `silkScriptLint` checks `silk.sh` with `bash -n`
- `.github/workflows/ci-script-smoke.yml` 的 runtime start smoke 同时检查 Web `/` 与 `/device` 返回静态 SPA
- Use:
  - before commit / push / PR when Kotlin, Gradle, or `silk.sh` changed
  - before blaming CI for fast-validation lint failures, because the same `silkLint` entrypoint runs in CI
- Maintenance:
  - `./gradlew silkLintBaseline` regenerates `config/lint/detekt/` baselines for intentionally accepted existing findings

## CC Bridge (Python)

- Command: `python3 -m pytest cc_bridge/tests/ -v`
- Primary tests:
  - `cc_bridge/tests/test_cc_session_index.py`

## Codex Bridge (Python)

- Command: `python3 -m pytest codex_bridge/tests/ -v`
- Primary tests:
  - `codex_bridge/tests/test_codex_session_index.py`
  - `codex_bridge/tests/test_codex_session_load.py`
  - `codex_bridge/tests/test_codex_dispatcher.py`
  - `codex_bridge/tests/test_codex_executor.py`
  - `codex_bridge/tests/test_fs_listing.py`

## When Payloads Change

同时触发：

- `:backend:test`
- `:frontend:webApp:nodeTest`
- `:frontend:androidApp:testDebugUnitTest`
- `:frontend:desktopApp:test`
