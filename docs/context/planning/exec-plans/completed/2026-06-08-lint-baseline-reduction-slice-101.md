# Lint Baseline Reduction Slice 101

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 101 完成历史，记录本轮继续清理 backend 中三处低风险 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 105 条降到 102 条。
- `ChatHistoryManager.kt` 删除了 `findAgentRepliesAfterMessage(...)` 中未使用的 `userTimestamp` 局部值。
- `ClaudeProcessClient.kt` 删除了未接线的 `claudePath` 私有属性，不影响 PTY 脚本解析或子进程启动。
- `GroupTodoExtractionService.kt` 的 LLM HTTP 非 200 失败出口从 `throw IllegalStateException(...)` 收敛为 `error(...)`，保持同样的 `IllegalStateException` 语义。

## Completed Slice

1. Slice 101: 清理 `ChatHistoryManager.kt` 与 `ClaudeProcessClient.kt` 的 2 条 `UnusedPrivateProperty` baseline。
2. Slice 101: 清理 `GroupTodoExtractionService.kt` 的 1 条 `UseCheckOrError` baseline。
3. Slice 101: 保持撤回消息、Claude PTY 调用与 group todo LLM 失败路径行为不变，只做实现层收敛。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一刀继续避开了大函数复杂度和文件级 broad-catch，优先把“明确无行为风险”的尾项清掉。
- backend 剩余 baseline 仍主要集中在 `TooGenericExceptionCaught`、`CyclomaticComplexMethod`、`NestedBlockDepth`、`LoopWithTooManyJumpStatements` 与 `LargeClass`。
