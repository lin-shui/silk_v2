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
| `frontend/webApp/` | Compose for Web 前端 | `Main.kt`, `AppState.kt`, `ApiClient.kt` |
| `frontend/androidApp/` | Android 前端 | `MainActivity.kt`, `AppState.kt`, `ApiClient.kt` |
| `frontend/desktopApp/` | Desktop 前端 | `Main.kt`, `AppState.kt`, `ApiClient.kt` |
| `frontend/harmonyApp/` | HarmonyOS ArkTS 前端 | `entry/src/main/ets/pages/*.ets`, `api/*.ets`, `stores/*.ets` |
| `search/` | Weaviate schema / indexing / startup 脚本（主线已由 Anthropic web_search + grep 替代） | `schema.py`, `indexer.py`, `start.sh` |
| `cc_bridge/` | Claude CLI ACP adapter | `acp_adapter.py`, `executor.py`, `session_manager.py`, `fs_listing.py` |
| `codex_bridge/` | Codex CLI ACP adapter | `codex_adapter.py`, `codex_executor.py`, `codex_session_index.py`, `fs_listing.py` |
| `feishu_bot/` | 飞书网关 | `main.py`, `silk_client.py`, `feishu_handler.py` |
| `docs/` | 现有项目文档与 agent workflow skill | `todo-roadmap.md`, `context/`, `skills/` |

## Backend Packages

| Package | Role |
| --- | --- |
| `auth/` | 注册登录、群组/联系人服务 |
| `database/` | Exposed tables / repositories |
| `ai/` | AIConfig、DirectModelAgent、tool policy |
| `search/` | Weaviate / 外部搜索（主线已由 AnthropicClient + grep searchContext 替代） |
| `agents/` | Agent 框架（`core/` 路由+状态、`acp/` ACP 协议层、`adapters/` Claude Code / Codex 描述符） |
| `todos/` | Todo 抽取、刷新、存储、节假日逻辑 |
| `workflow/` | Workflow JSON store |
| `kb/` | Knowledge Base JSON store + exporter |
| `trust/` | Workflow directory trust store |
| `export/` | Chat 导出 |
| `routes/` | 已拆出的文件/ASR 路由 |
| `utils/` | 网页下载器等 |

## Frontend Surfaces

| Surface | Notes |
| --- | --- |
| `frontend/shared` | Web/Android/Desktop 共用；Harmony 不用 |
| `frontend/webApp` | 具备 Silk / Workflow / Knowledge Base / Audio Duplex 四 Tab |
| `frontend/androidApp` | 具备 Silk / Workflow / Knowledge Base / Audio Duplex 四 Tab，聊天页和工作流会话页隐藏底栏 |
| `frontend/desktopApp` | 仍是较早 UI 面；当前无 Workflow / KB / Audio Duplex 主壳 |
| `frontend/harmonyApp` | 独立实现，含 Todo / Workflow / KB / Audio Duplex 页面 |

## Tests

- Backend: `backend/src/test/kotlin/com/silk/backend/`
- Web: `frontend/webApp/src/test/kotlin/com/silk/web/FileContractsTest.kt`
- Android: `frontend/androidApp/src/test/kotlin/com/silk/android/FileContractsTest.kt`
- Android workflow path helpers: `frontend/androidApp/src/test/kotlin/com/silk/android/WorkflowPathUtilsTest.kt`
- Desktop: `frontend/desktopApp/src/test/kotlin/com/silk/desktop/FileContractsTest.kt`
