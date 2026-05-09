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

## Trusted Directory

- Command: `./gradlew :backend:test`
- Primary tests:
  - `trust/TrustedDirManagerTest`

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
  - `frontend/androidApp/src/test/kotlin/com/silk/android/WorkflowPathUtilsTest.kt`

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
  - `.github/workflows/ci-script-smoke.yml` 覆盖 `./silk.sh build`、`./silk.sh build-apk` 与 `./silk.sh build-all` 编排 smoke

## Kotlin / Script Lint

- Command: `./gradlew silkLint`
- Coverage:
  - detekt checks Kotlin source across `backend` and Gradle frontends
  - `silkScriptLint` checks `silk.sh` with `bash -n`
- Maintenance:
  - `./gradlew silkLintBaseline` regenerates `config/lint/detekt/` baselines for intentionally accepted existing findings

## When Payloads Change

同时触发：

- `:backend:test`
- `:frontend:webApp:nodeTest`
- `:frontend:androidApp:testDebugUnitTest`
- `:frontend:desktopApp:test`
