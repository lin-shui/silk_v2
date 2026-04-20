# Android

## Entry Surface

- `frontend/androidApp/src/main/kotlin/com/silk/android/MainActivity.kt`
- `AppState.kt`
- `ApiClient.kt`
- `ChatScreen.kt`
- `GroupListScreen.kt`
- `WorkflowScreen.kt`
- `KnowledgeBaseScreen.kt`
- `SettingsScreen.kt`

## Current Shape

- Jetpack Compose + Material 3
- 登录后主壳是底部三 Tab
- 聊天页隐藏底栏
- 包含版本检查 / APK 下载 / 文件处理 / ASR

## Build-Time Facts

- `build.gradle.kts` 会按构建时间生成 `versionCode` / `versionName`
- `BuildConfig.BACKEND_BASE_URL` 从 `.env` 或 Gradle 属性注入
- CI 只跑：
  - `testDebugUnitTest`
  - `compileDebugKotlin`

## Watch Points

- 改文件 payload 时看 `FileContracts.kt` / `FileContractsTest.kt`
- 改导航壳层时看 `AppState.kt` 与 `MainActivity.kt`
- 改后端地址逻辑时同时检查 `.env.example`
