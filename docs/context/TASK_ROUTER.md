# Task Router

按任务加载最小上下文，不要默认把所有文档都读一遍。

## Closeout Pass

完成实现后，按实际改动路径检查是否需要同步文档：

- 新增、移动或删除主要入口/模块：更新 `ARCHITECTURE.md`、`generated/REPO_MAP.md` 或对应 `modules/**/INDEX.md`
- 改 HTTP/WebSocket/payload 合同：更新 backend/frontend 相关 context，并确认 `quality/TEST_MATRIX.md` 的验证映射仍正确
- 改 `silk.sh`、环境变量、CI workflow 或构建命令：更新 `project/BOOTSTRAP.md` 与 `quality/*`
- 发现代码和文档事实不一致但本轮不修：更新 `project/KNOWN_DRIFT.md`
- Todo roadmap 例外：默认只读 `docs/todo-roadmap.md`，计划和记录写入 `planning/exec-plans/`

## Commit / Push / PR

- 先读：`../skills/local-change-submit/SKILL.md`
- 再看：`git status --short --branch`、目标 base、当前分支是否属于已有 PR
- 默认验证：按实际改动运行最窄验证，并跑 `git diff --check`
- 规则：只提交本轮相关路径；push/PR 前确认文档同步门禁、命名规范与 PR body 信息齐全

## Backend HTTP / Route

- 先读：`ARCHITECTURE.md`、`modules/backend/INDEX.md`、`modules/backend/CHAT_AND_ROUTING.md`
- 再看代码：`Application.kt`、`Routing.kt`、`routes/FileRoutes.kt`、`routes/AsrRoutes.kt`
- 若改返回体/消息合同：再读 `modules/frontend/SHARED.md`
- 默认验证：`./gradlew :backend:test`

## Chat / WebSocket / Message Flow

- 先读：`modules/backend/CHAT_AND_ROUTING.md`、`project/RUNTIME_AND_STORAGE.md`
- 再看代码：`WebSocketConfig.kt`、`ChatHistoryManager.kt`、`frontend/shared/.../Message.kt`、`ChatClient.kt`
- 默认验证：`./gradlew :backend:test`

## AI / Tool Calling / Search

- 先读：`modules/backend/AI_AND_INTEGRATIONS.md`、`integrations/SEARCH_AND_AUX_SERVICES.md`
- 再看代码：`ai/DirectModelAgent.kt`、`ai/ToolPolicyManager.kt`、`search/WeaviateClient.kt`、`search/ExternalSearchService.kt`
- 默认验证：`./gradlew :backend:test`
- 若改工具暴露或路径策略：必须覆盖 `DirectModelAgentToolPolicyTest`

## File Upload / Download / URL Ingestion

- 先读：`modules/backend/CHAT_AND_ROUTING.md`、`modules/frontend/SHARED.md`、`quality/TEST_MATRIX.md`
- 再看代码：`routes/FileRoutes.kt`、`WebSocketConfig.kt`、`utils/WebPageDownloader.kt`
- 默认验证：
  - `./gradlew :backend:test`
  - `./gradlew :frontend:webApp:nodeTest :frontend:androidApp:testDebugUnitTest :frontend:desktopApp:test`

## Todo

- 先读：`planning/TODO_ROADMAP.md`、`modules/backend/DOMAIN_STORAGE_AND_FEATURES.md`
- 再看代码：`todos/`、`Routing.kt` 中 `/api/user-todos*`、Harmony Todo 页面
- 默认验证：`./gradlew :backend:test`
- 规则：先对齐 `docs/todo-roadmap.md`；默认不要由 agent 自动维护 roadmap，本轮若需要实施计划或建议，写入 `planning/exec-plans/`

## Workflow / Knowledge Base

- 先读：`modules/backend/DOMAIN_STORAGE_AND_FEATURES.md`、`modules/frontend/INDEX.md`
- 再看代码：`workflow/WorkflowManager.kt`、`kb/KnowledgeBaseManager.kt`、Web/Android/Harmony 对应页面
- 默认验证：
  - `./gradlew :backend:test`
  - 受影响前端的编译/单测

## Web Frontend

- 先读：`modules/frontend/WEB.md`、`modules/frontend/SHARED.md`
- 再看代码：`frontend/webApp/src/main/kotlin/com/silk/web/`
- 默认验证：
  - `./gradlew :frontend:webApp:nodeTest`
  - `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs`

## Android Frontend

- 先读：`modules/frontend/ANDROID.md`、`modules/frontend/SHARED.md`
- 再看代码：`frontend/androidApp/src/main/kotlin/com/silk/android/`
- 默认验证：
  - `./gradlew :frontend:androidApp:testDebugUnitTest`
  - `./gradlew :frontend:androidApp:compileDebugKotlin`

## Desktop Frontend

- 先读：`modules/frontend/DESKTOP.md`、`modules/frontend/SHARED.md`
- 再看代码：`frontend/desktopApp/src/main/kotlin/com/silk/desktop/`
- 默认验证：
  - `./gradlew :frontend:desktopApp:test`
  - `./gradlew :frontend:desktopApp:compileKotlin`

## Harmony Frontend

- 先读：`modules/frontend/HARMONY.md`
- 再看代码：`frontend/harmonyApp/entry/src/main/ets/`
- 默认验证：本地环境具备 DevEco/hvigor/hdc 时，按文档里的 sync + assembleHap + install 路径
- 注意：Harmony 不复用 `frontend/shared`

## Claude Code / Bridge / Feishu

- 先读：`integrations/CLAUDE_CODE_AND_BRIDGES.md`
- 再看代码：`backend/claudecode/`、`cc_bridge/`、`feishu_bot/`
- 默认验证：
  - 后端侧 `./gradlew :backend:test`
  - Python 服务按各自 README 或手动 smoke

## CI / Validation / Tooling

- 先读：`quality/INDEX.md`、`quality/CI_FAST_VALIDATION_SCOPE.md`
- 若改 `silk.sh` / 装配 smoke：再读 `quality/CI_SCRIPT_SMOKE_SCOPE.md`
- 再看代码：`.github/workflows/ci-fast-validation.yml`、`backend/src/test/.../README_TESTS.md`
- 默认验证：优先复用 CI 中已有的窄检查，不凭空创造一套新的重流程
