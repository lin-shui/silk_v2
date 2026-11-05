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

## dsh-superagent Bot Surface

- Command: `./gradlew :backend:test`
- Primary test:
  - `routes/DshBotRoutesTest` — token 生命周期、按邀请码入群、时间游标拉取、幂等发送

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

## Web File Contract / Parser

- Commands:
  - `./gradlew :frontend:webApp:nodeTest`
  - `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs`
- Primary test:
  - `frontend/webApp/src/test/kotlin/com/silk/web/FileContractsTest.kt`

## Android File Contract / Parser

- Commands:
  - `./gradlew :frontend:androidApp:testDebugUnitTest`
  - `./gradlew :frontend:androidApp:compileDebugKotlin`
- Primary tests:
  - `frontend/androidApp/src/test/kotlin/com/silk/android/FileContractsTest.kt`

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

## When Payloads Change

同时触发：

- `:backend:test`
- `:frontend:webApp:nodeTest`
- `:frontend:androidApp:testDebugUnitTest`
- `:frontend:desktopApp:test`
