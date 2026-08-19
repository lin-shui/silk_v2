# Repo Map

## Ignore First

这些路径通常不是阅读入口：

- `backend/bin/`
- `frontend/desktopApp/bin/`
- `build/`
- `backend/build/`
- `.gradle/`
- `.silk-runtime/`
- `kotlin-js-store/`

## Top Level

| Path | Role | Primary Entry Files |
| --- | --- | --- |
| `backend/` | Ktor backend | `Application.kt`, `Routing.kt`, `WebSocketConfig.kt` |
| `frontend/shared/` | 跨端 KMP 合同面 | `ChatClient.kt`, `models/Message.kt`, `models/ApiResponses.kt` |
| `frontend/webApp/` | Compose for Web 前端 | `Main.kt`, `AppState.kt`, `ApiClient.kt`, `DeviceManagementScene.kt` |
| `frontend/androidApp/` | Android 前端 | `MainActivity.kt`, `AppState.kt`, `ApiClient.kt` |
| `frontend/desktopApp/` | Desktop 前端 | `Main.kt`, `AppState.kt`, `ApiClient.kt` |
| `frontend/harmonyApp/` | HarmonyOS ArkTS 前端 | `entry/src/main/ets/pages/*.ets`, `api/*.ets`, `stores/*.ets` |
| `search/` | Weaviate schema / indexing / startup 脚本（主线已由 Anthropic web_search + grep 替代） | `schema.py`, `indexer.py`, `start.sh` |
| `cc_bridge/` | Claude CLI ACP adapter | `acp_adapter.py`, `executor.py`, `cc_session_index.py`, `fs_listing.py` |
| `codex_bridge/` | Codex CLI ACP adapter and managed app-server approval transport | `codex_adapter.py`, `codex_executor.py`, `codex_app_server.py`, `codex_session_index.py`, `fs_listing.py` |
| `bridge_common/` | Host-managed direct Bridge IPC v2 和每轮 CLI 执行权限策略 | `host_ipc.py`, `execution_policy.py` |
| `feishu_bot/` | 飞书网关 | `main.py`, `silk_client.py`, `feishu_handler.py` |
| `silk-agent/` | 外部 Agent 统一 Host（Go companion；设备认证、系统凭据/加密备份、单一 WSS 多路复用、签名新增 Agent/热加载、Host 生命周期、受控 Adapter supervision/stdio IPC v2 和签名发行包） | `main.go`, `connection.go`, `host.go`, `identity.go`, `credential_store_*.go`, `release.go`, `adapter_supervisor.go`, `adapters/`, `scripts/package-release.sh` |
| `scripts/` | 轻量运行辅助脚本 | `silk_static_server.py`（Web 静态服务 + `/device` SPA fallback） |
| `docs/` | 现有项目文档与 agent workflow skill | `todo-roadmap.md`, `context/`, `skills/` |

## Backend Packages

| Package | Role |
| --- | --- |
| `auth/` | 注册登录、群组/联系人服务 |
| `database/` | Exposed tables / repositories |
| `ai/` | AIConfig、DirectModelAgent、tool policy |
| `search/` | Weaviate / 外部搜索（主线已由 AnthropicClient + grep searchContext 替代） |
| `agents/` | Agent 框架（`core/` 路由+状态/Binding 触发、`acp/` ACP 协议层、`adapters/` Claude Code / Codex 描述符、`auth/` 设备公钥配对、challenge-response 与 Binding 授权） |
| `todos/` | Todo 抽取、刷新、存储、节假日逻辑 |
| `workflow/` | Workflow JSON store |
| `kb/` | Knowledge Base 存储（JSON / PostgreSQL + pgvector 双后端）+ exporter + AI Copilot（`KnowledgeBaseCopilot.kt`）+ AI Actions（`KnowledgeBaseAiActions.kt`）+ Memory Layer（`KnowledgeBaseMemory.kt`）+ Prompt Context（`KnowledgeBasePromptContext.kt`）+ Context Preference（`KnowledgeBaseContextPreferenceStore.kt`）+ Embedding（`KnowledgeBaseEmbedding.kt`）+ PG 仓库（`PgKnowledgeBaseRepository.kt`）+ Obsidian 导出 |
| `trust/` | Workflow directory trust store |
| `export/` | Chat 导出 |
| `routes/` | 已拆出的文件/ASR/Agent 设备认证等路由；新认证入口为 `AgentAuthRoutes.kt` |
| `utils/` | 网页下载器等 |

## Frontend Surfaces

| Surface | Notes |
| --- | --- |
| `frontend/shared` | Web/Android/Desktop 共用；Harmony 不用 |
| `frontend/webApp` | 具备 Silk / Workflow / Knowledge Base / Audio Duplex 四 Tab，以及 `/device` 外部 Agent 配对与管理页 |
| `frontend/androidApp` | 具备 Silk / Workflow / Knowledge Base / Audio Duplex 四 Tab，聊天页和工作流会话页隐藏底栏 |
| `frontend/desktopApp` | 仍是较早 UI 面；当前无 Workflow / KB / Audio Duplex 主壳 |
| `frontend/harmonyApp` | 独立实现，含 Todo / Workflow / KB / Audio Duplex 页面 |

## Tests

- Backend: `backend/src/test/kotlin/com/silk/backend/`
- Agent device auth: `AgentAuthRouteContractTest.kt`, `AgentBindingApprovalRouteContractTest.kt`, `AgentBindingTeamRoutingContractTest.kt`, `agents/auth/AgentAuthProtocolTest.kt`, `agents/auth/AgentBindingAuthorizationTest.kt`, `agents/acp/AcpMultiplexedTransportTest.kt`, `agents/core/AgentBindingTriggerMatcherTest.kt`
- Web: `frontend/webApp/src/test/kotlin/com/silk/web/FileContractsTest.kt`, `DeviceManagementModelsTest.kt`
- Android: `frontend/androidApp/src/test/kotlin/com/silk/android/FileContractsTest.kt`
- Android workflow path helpers: `frontend/androidApp/src/test/kotlin/com/silk/android/WorkflowPathUtilsTest.kt`
- Desktop: `frontend/desktopApp/src/test/kotlin/com/silk/desktop/FileContractsTest.kt`
