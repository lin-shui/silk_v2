# Lint Baseline Reduction Slice 109

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 109 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoRefreshAsyncManager.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 89 条降到 88 条。
- `UserTodoRefreshAsyncManager.kt` 的后台刷新任务不再使用 `catch (e: Exception)`；现改为 `runCatching` 统一处理成功/失败分支。
- 异步待办刷新失败日志从 `println + printStackTrace` 收紧为结构化 `logger.error(...)`，同时保留 `lastError` 状态回写给前端轮询。

## Completed Slice

1. Slice 109: 清理 `UserTodoRefreshAsyncManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 109: 保持后台待办刷新成功后写回 `lastFinishedAt`、失败后写回 `lastError` 的既有状态合同，不改变 `start()` / `status()` 返回体。
3. Slice 109: 让正常 `CancellationException` 不再被误当成业务错误写回状态，避免应用关闭或协程取消时污染异步刷新结果。

## Validation

- `./gradlew :backend:test`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮选择 `UserTodoRefreshAsyncManager.kt`，是因为它是当前 backend 剩余 broad-catch 里最小的单文件单职责切片，且不牵涉 HTTP / WebSocket / 消息合同。
- 后续若继续沿着 Todo 面推进，优先看 `UserTodoStore.kt` 或 `GroupTodoExtractionService.kt` 的同类异常语义，但要避免把复杂度收敛和 broad-catch 收敛混在一轮里。
