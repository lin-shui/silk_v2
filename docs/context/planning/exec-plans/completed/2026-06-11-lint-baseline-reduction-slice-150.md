# Lint Baseline Reduction Slice 150

这份归档保留 `lint-baseline-reduction` 的 Slice 150 完成历史，记录本轮在 `backend/src/test/kotlin/com/silk/backend/agents/acp/AcpClientTest.kt` 上继续收敛 detekt 的 `TooGenericExceptionThrown` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `AcpClientTest.kt$throw RuntimeException("boom")` 对应的 1 条 `TooGenericExceptionThrown` baseline。
- `handler throwing does not kill receive loop` 测试里把 broad throw 收紧为 `IllegalStateException("boom")`，保留“handler 失败后 receive loop 继续存活、后续 RPC 仍可完成”的测试意图不变。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮先收掉 backend detekt 里唯一的测试侧 `TooGenericExceptionThrown` 尾项，没有把 `Routing.kt` / `GroupTodoExtractionService.kt` 的主源码异常面混进来。
