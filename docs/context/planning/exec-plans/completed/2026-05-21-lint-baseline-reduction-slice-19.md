# Lint Baseline Reduction Slice 19

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 19 完成历史，记录本轮在 `frontend/webApp` 做的一组低风险异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 40 条降到 35 条。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 从 11 条降到 6 条。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 19: 为 `frontend/webApp` 新增透传 `CancellationException` 的恢复 helper，并将以下文件的显式泛 catch 改成 `runCatching` 恢复逻辑，删除对应 5 条 `TooGenericExceptionCaught` baseline：
   - `AppState.kt`
   - `ContactsScene.kt`
   - `LoginScene.kt`
   - `KnowledgeBaseScene.kt`
   - `ObsidianVaultManager.kt`

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 新增 helper 位于 `frontend/webApp/src/main/kotlin/com/silk/web/ExceptionRecovery.kt`，只负责“非 cancellation 错误恢复”，避免 UI / 浏览器 API 的错误回退把协程取消吞掉。
- `ApiClient.kt` 仍然不适合按单条 baseline 直接切；它的异常类 baseline 粒度较粗，下一次如果处理，建议按整组 request helper / 调用点一起收敛。
