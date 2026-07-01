# Lint Baseline Reduction Slice 47

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 47 完成历史，记录本轮在 `frontend/androidApp` 的 `ContactsScreen.kt` 上做的 API 恢复路径收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 49 条降到 48 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ContactsScreen.kt` 清掉了 `TooGenericExceptionCaught` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 47: 删除 `ContactsScreen.kt` 里包裹 `ApiClient.getUserSettings(...)` 和 `ApiClient.getContacts(...)` 的两处 `catch (Exception)`，改为直接依赖 `ApiClient.recoverApiCall(...)` 已经提供的失败兜底；联系人页继续保留“加载用户语言失败时沿用默认中文、联系人接口失败时保留空列表并打日志”的原有语义，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮没有额外跑 `:frontend:androidApp:testDebugUnitTest`；改动只落在联系人页的本地加载/日志分支，继续按 active plan 走最窄 lint 验证链路。
- Android 侧剩余 `TooGenericExceptionCaught` 已降到 8 条；下一轮优先继续挑 `SettingsScreen.kt`、`LoginScreen.kt`、`WorkflowChatScreen.kt` 这种只有 1-2 个 generic catch 的小文件，不急着把 `ChatScreen.kt` 整页拖进来。
