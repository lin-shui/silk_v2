# Lint Baseline Reduction Slices 1-16

## Scope

这份归档保留 `lint-baseline-reduction` 在 Slice 1-16 的完成历史，供后续 agent 快速恢复已清理过的代码面、验证方式和已知环境阻塞。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- 根 lint 入口保持为 `./gradlew silkLint`，没有提交全量 `silkLintBaseline` 再生结果。
- `frontend/desktopApp` detekt baseline 已清空。
- `frontend/androidApp` 的 `WildcardImport` baseline 已从 47 条清到 0 条。
- `frontend/webApp`、`frontend/shared` 已无 `WildcardImport` baseline。
- 截至 2026-05-21，detekt baseline 余量为：
  - `backend.xml`: 222
  - `frontend-androidApp.xml`: 57
  - `frontend-webApp.xml`: 45
  - `frontend-shared.xml`: 7
  - `frontend-desktopApp.xml`: 0

## Completed Slices

1. Slice 1: 清理 `frontend/shared` 的 `WildcardImport` baseline，并跑 shared 编译与 `silkLint`。
2. Slice 2: 清理 backend 入口层 `WildcardImport`，覆盖 `Application.kt`、`Routing.kt`、`routes/AsrRoutes.kt`、`routes/FileRoutes.kt`，跑 `:backend:test`。
3. Slice 3: 清理 `frontend/webApp` 的 `WildcardImport` baseline，并跑 web detekt / nodeTest。
4. Slice 4: 清理 `frontend/shared` 的明确未使用私有值。
5. Slice 5: 收敛 shared WebSocket / ChatClient 的异常处理规则，显式透传 cancellation，不再泛捕。
6. Slice 6: 清理 `frontend/desktopApp` 的 `WildcardImport` baseline。
7. Slice 7: 清理 `frontend/desktopApp` 的未使用私有状态 / 参数 / helper。
8. Slice 8: 清理 `frontend/desktopApp` 非 `Main.kt` / `MessageContextMenu.kt` 的低风险异常类问题。
9. Slice 9: 清理 `frontend/desktopApp` 中 `Main.kt` / `MessageContextMenu.kt` 的异常处理、吞异常、`PrintStackTrace` 与局部嵌套深度问题。
10. Slice 10: 拆分 `frontend/desktopApp` 剩余复杂度 baseline，清空 desktop baseline。
11. Slice 11: 清理 `frontend/androidApp` 的未使用参数 / 私有状态，并顺手消掉 `AudioDuplexScreen.kt`、`GroupListScreen.kt` 的复杂度 baseline。
12. Slice 12: 清理 `frontend/webApp` 一批低风险未使用项 / 命名问题，保持复杂度 baseline 不回填。
13. Slice 13: 清理 `frontend/androidApp` 一批辅助/工作流/知识库界面的 `WildcardImport`。
14. Slice 14: 清理 `frontend/androidApp` 中 `LoginScreen.kt`、`MainActivity.kt`、`WorkflowChatScreen.kt` 的 `WildcardImport`。
15. Slice 15: 清理 `frontend/androidApp` 中 `ContactsScreen.kt`、`GroupListScreen.kt`、`SettingsScreen.kt` 的 `WildcardImport`。
16. Slice 16: 清理 `frontend/androidApp` 中 `ApiClient.kt`、`AudioDuplexScreen.kt`、`ChatScreen.kt` 的 12 条 `WildcardImport` baseline。

## Validation Pattern Reused Across Slices

- 基线命令：`./gradlew silkLint`
- Android slice 常用：
  - `./gradlew :frontend:androidApp:detekt --no-daemon --warning-mode none --console=plain`
  - `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
  - `./gradlew :frontend:androidApp:detekt :frontend:androidApp:testDebugUnitTest silkLint --no-daemon --warning-mode none --console=plain`
- Desktop slice 常用：
  - `./gradlew :frontend:desktopApp:detekt`
  - `./gradlew :frontend:desktopApp:test :frontend:desktopApp:compileKotlin silkLint`
- Web slice 常用：
  - `./gradlew :frontend:webApp:detekt --no-daemon --stacktrace --warning-mode all`
  - `./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs silkLint --no-daemon --stacktrace --warning-mode all`
- 通用：`git diff --check`

## Known Environment Drift

- Android 相关 slice 的 `testDebugUnitTest` 仍可能阻塞在 `:frontend:androidApp:compileDebugJavaWithJavac`。
- 失败形态是 `JdkImageTransform` 调 `jlink` 处理 `android-34/core-for-system-modules.jar`，落点在工具链环境，不是这些 slice 引入的 Kotlin 编译错误。
