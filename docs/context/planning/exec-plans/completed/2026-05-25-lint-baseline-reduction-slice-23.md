# Lint Baseline Reduction Slice 23

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 23 完成历史，记录本轮在 `frontend/webApp` 的 `Main.kt` 上继续做的一组低风险异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 32 条降到 30 条。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 从 3 条降到 1 条。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 23: 继续复用 `ExceptionRecovery.kt` 的 cancellation-safe 恢复 helper，清理 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中导出 Markdown、ASR 转写、文件列表加载、Markdown 数学渲染这组显式 `catch (t/error: Throwable)`，并删除对应 2 条 `TooGenericExceptionCaught` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮保留了原有 `finally` 语义，确保导出与 ASR 转写过程里的 `isExportingMarkdown`、`isTranscribing` 状态在协程取消时仍会复位。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 已只剩 `ApiClient.kt` 1 条；下一步更适合继续收敛 `Main.kt` 的 `dynamic` catch，或把 `ApiClient.kt` 的 helper 成组处理掉。
