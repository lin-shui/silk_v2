# Lint Baseline Reduction Slices 17-18

## Scope

这份归档保留 `lint-baseline-reduction` 在 Slice 17-18 的完成历史，供后续 agent 快速恢复最近两步收敛过的代码面、验证方式和已知阻塞。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend` 已清空 `WildcardImport` baseline。
- `frontend/webApp` 已清空 `UnusedParameter` baseline。
- 截至 2026-05-21，detekt baseline 余量为：
  - `backend.xml`: 186
  - `frontend-androidApp.xml`: 57
  - `frontend-webApp.xml`: 40
  - `frontend-shared.xml`: 7
  - `frontend-desktopApp.xml`: 0

## Completed Slices

1. Slice 17: 清理 backend 剩余 36 条 `WildcardImport` baseline，覆盖 `WebSocketConfig.kt`、`ChatHistory*`、`AuthService.kt`、`GroupService.kt`、`*Repository.kt`、`AcpWebSocketTransport.kt`、`AIStepwiseAgent.kt`、`AnthropicClient.kt`、`DirectModelAgent.kt`、`SearchDrivenAgent.kt`、`ExternalSearchService.kt`、`WeaviateClient.kt`、`WebPageDownloader.kt`。
2. Slice 18: 将 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 里的 `highlight.js` / `dompurify` 互操作从 `external object` 参数声明改成动态模块包装函数，删除 5 条 `UnusedParameter` baseline。

## Validation

- Slice 17:
  - `./gradlew :backend:compileKotlin --no-daemon --warning-mode none --console=plain`
  - `./gradlew :backend:test silkLint --no-daemon --warning-mode none --console=plain`
  - `git diff --check`
- Slice 18:
  - `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
  - `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
  - `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
  - `git diff --check`

## Known Environment Drift

- Android 相关 slice 的 `testDebugUnitTest` 仍可能阻塞在 `:frontend:androidApp:compileDebugJavaWithJavac`。
- 失败形态是 `JdkImageTransform` 调 `jlink` 处理 `android-34/core-for-system-modules.jar`，落点在工具链环境，不是这些 slice 引入的 Kotlin 编译错误。
