# Lint Baseline Reduction Slice 22

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 22 完成历史，记录本轮在 `frontend/webApp` 的 `Main.kt` 上做的一组低风险异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 33 条降到 32 条。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 从 4 条降到 3 条。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 22: 复用 `ExceptionRecovery.kt` 的 cancellation-safe 恢复 helper，清理 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中聊天室侧栏群组刷新、未读轮询、聊天页语言偏好加载、更换 Obsidian Vault 目录、撤回消息、删除消息这组显式 `catch (e: Exception)`，并删除对应 1 条 `TooGenericExceptionCaught` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮保留了原有 `finally` 语义，确保侧栏加载与撤回中的状态位在协程取消时也能复位。
- `frontend/webApp` 剩余异常语义问题已经集中到 `Main.kt` 的 `Throwable` / `dynamic` catch 和 `ApiClient.kt` 的整组 request helper；前者仍可切小，后者建议按 helper 成组处理。
