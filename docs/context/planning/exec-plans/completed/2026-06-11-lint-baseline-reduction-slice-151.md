# Lint Baseline Reduction Slice 151

这份归档保留 `lint-baseline-reduction` 的 Slice 151 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- LLM 抽取与去冗入口统一改走 `callLlmOrNull(...)`：正常 `CancellationException` 继续透传，`InterruptedException` 会恢复线程中断标志，其余已知 I/O / 解析 / 状态异常按原语义记录失败并回退。
- `parseCompactTodoJson(...)` 与 `parseTodoJsonStrict(...)` 现在只对 JSON decode / shape 异常降级，不再用 broad catch 吞掉其它运行时错误。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮继续沿用 “同文件、单职责、先异常语义后大重构” 的策略，没有碰 `refreshTodosForUser(...)` 的复杂度主项，也没有把 `Routing.kt` 的聚合 catch 混进来。
