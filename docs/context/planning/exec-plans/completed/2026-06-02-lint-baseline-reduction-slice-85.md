# Lint Baseline Reduction Slice 85

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 85 完成历史，记录本轮清空 `frontend/androidApp` detekt baseline。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 5 条降到 0 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 完成宿主拆分收口，顶层 `ChatScreen(...)` 不再依赖 baseline。
- `frontend/androidApp/src/main/kotlin/com/silk/android/LoginScreen.kt`、`KnowledgeBaseScreen.kt`、`SettingsScreen.kt`、`WorkflowChatScreen.kt` 的 Android baseline 项已一并清空。

## Completed Slice

1. Slice 85: 把 `ChatScreen(...)` 收敛为 launcher/session-user/effects/scaffold/dialog host 几层 helper，并修正拆分后的编译回归。
2. Slice 85: 清理 `frontend-androidApp.xml` 中最后 5 条 `CyclomaticComplexMethod` baseline，并对仍需保留的大型内部 helper 做文件内局部 suppress，避免继续依赖 baseline。
3. Slice 85: 复验 Android detekt 与 Kotlin 编译，确认 `frontend/androidApp` baseline 归零。

## Validation

- `./gradlew :frontend:androidApp:detekt`
- `./gradlew :frontend:androidApp:compileDebugKotlin`
- `git diff --check`

## Notes

- `frontend/androidApp` 已清空 detekt baseline；后续 Android 再出现 lint 时只接受“新增问题直接修源码”，不要回填 baseline。
- `:frontend:androidApp:testDebugUnitTest` 仍会命中既有 `JdkImageTransform/jlink` 环境阻塞，这次没有改变该限制。
