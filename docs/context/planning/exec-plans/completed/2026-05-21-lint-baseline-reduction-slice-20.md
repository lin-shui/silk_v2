# Lint Baseline Reduction Slice 20

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 20 完成历史，记录本轮在 `frontend/webApp` 的 `SettingsScene.kt` 上做的异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 35 条降到 34 条。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 从 6 条降到 5 条。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 20: 复用 `ExceptionRecovery.kt` 的 cancellation-safe 恢复 helper，清理 `frontend/webApp/src/main/kotlin/com/silk/web/SettingsScene.kt` 中加载设置、刷新 Bridge 状态、保存设置这 3 处显式泛 catch，并删除对应 1 条 `TooGenericExceptionCaught` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮保留了原有 `finally` 语义，确保 `isLoading`、`ccIsTesting`、`isSaving` 在协程取消时也能复位。
- `frontend/webApp` 剩余异常语义问题里，`Main.kt` / `GroupListScene.kt` 仍然是下一轮更合适的低风险候选；`ApiClient.kt` 继续建议按整组 helper 一起收敛，不要按单个 catch 硬切。
