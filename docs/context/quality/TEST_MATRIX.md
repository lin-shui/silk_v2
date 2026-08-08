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
  - `agents/core/AgentRuntimeTest`
  - `agents/core/AgentRuntimeAcpIntegrationTest`
  - `agents/core/AgentSessionTest`
  - `agents/core/CommandRouterTest`
  - `agents/core/GroupAgentContextTest`
  - `agents/core/AcpUpdateMapperTest`
  - `agents/acp/AcpClientTest`
  - `agents/acp/AcpRegistryTest`

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
