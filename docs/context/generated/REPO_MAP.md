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
| `search/` | Weaviate schema / indexing / startup 脚本 | `schema.py`, `indexer.py`, `start.sh` |
| `cc_bridge/` | Claude CLI bridge | `bridge_agent.py`, `executor.py`, `session_manager.py` |
| `feishu_bot/` | 飞书网关 | `main.py`, `silk_client.py`, `feishu_handler.py` |
| `docs/` | 现有项目文档与 agent workflow skill | `todo-roadmap.md`, `context/`, `skills/` |

## Backend Packages

| Package | Role |
| --- | --- |
| `auth/` | 注册登录、群组/联系人服务 |
| `database/` | Exposed tables / repositories |
| `ai/` | AIConfig、DirectModelAgent、tool policy |
| `search/` | Weaviate / 外部搜索 |
| `claudecode/` | Claude Code 模式与 bridge registry |
| `todos/` | Todo 抽取、刷新、存储、节假日逻辑 |
| `workflow/` | Workflow JSON store |
| `kb/` | Knowledge Base JSON store + exporter |
| `export/` | Chat 导出 |
| `routes/` | 已拆出的文件/ASR 路由 |
| `utils/` | 网页下载器等 |

## Frontend Surfaces

| Surface | Notes |
| --- | --- |
| `frontend/shared` | Web/Android/Desktop 共用；Harmony 不用 |
| `frontend/webApp` | 具备 Silk / Workflow / Knowledge Base 三 Tab |
| `frontend/androidApp` | 具备三 Tab，聊天页隐藏底栏 |
| `frontend/desktopApp` | 仍是较早 UI 面；当前无 Workflow / KB 主壳 |
| `frontend/harmonyApp` | 独立实现，含 Todo / Workflow / KB 页面 |

## Tests

- Backend: `backend/src/test/kotlin/com/silk/backend/`
- Web: `frontend/webApp/src/test/kotlin/com/silk/web/FileContractsTest.kt`
- Android: `frontend/androidApp/src/test/kotlin/com/silk/android/FileContractsTest.kt`
- Desktop: `frontend/desktopApp/src/test/kotlin/com/silk/desktop/FileContractsTest.kt`
