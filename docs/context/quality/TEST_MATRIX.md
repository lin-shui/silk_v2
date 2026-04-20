# Test Matrix

## Backend Route / Contract

- Command: `./gradlew :backend:test`
- Primary tests:
  - `BackendHttpContractTest`
  - `BackendFileContractTest`
  - `BackendWebSocketContractTest`

## AI Tool Policy / Search / URL Download

- Command: `./gradlew :backend:test`
- Primary tests:
  - `ai/DirectModelAgentToolPolicyTest`
  - `utils/WebPageDownloaderSmokeTest`

## Todo Lifecycle

- Command: `./gradlew :backend:test`
- Primary tests:
  - `todos/UserTodoStoreTest`

## Claude Code Metadata / Parser Surface

- Command: `./gradlew :backend:test`
- Primary tests:
  - `claudecode/StreamParserTest`

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
- Primary test:
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

## When Payloads Change

同时触发：

- `:backend:test`
- `:frontend:webApp:nodeTest`
- `:frontend:androidApp:testDebugUnitTest`
- `:frontend:desktopApp:test`
