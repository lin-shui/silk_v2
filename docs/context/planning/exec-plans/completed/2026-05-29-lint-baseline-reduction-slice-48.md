# Lint Baseline Reduction Slice 48

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 48 完成历史，记录本轮在 `frontend/androidApp` 的 `SettingsScreen.kt` 上做的 API 恢复路径收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 48 条降到 47 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/SettingsScreen.kt` 清掉了 `TooGenericExceptionCaught` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 48: 删除 `SettingsScreen.kt` 里包裹 `ApiClient.getUserSettings(...)` / `ApiClient.getCcSettings(...)` / `ApiClient.updateUserSettings(...)` 的 generic `catch (Exception)`，改为直接依赖 `ApiClient.recoverApiCall(...)` 已经提供的失败兜底；设置页继续保留“用户设置加载失败时回退默认语言与默认指令、CC Bridge 状态失败时只打日志、保存失败时展示错误提示”的原有语义，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮保留了 `finally` 来统一回收 `isLoading` / `isSaving`，但不再吞掉 coroutine cancellation；页面离开时取消会自然向上透传。
- Android 侧剩余 `TooGenericExceptionCaught` 已降到 7 条；下一轮优先继续挑 `LoginScreen.kt`、`WorkflowChatScreen.kt`、`GroupListScreen.kt` 这种只有 1-2 个 generic catch 的小文件，不急着把 `ChatScreen.kt` 整页拖进来。
