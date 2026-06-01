# Lint Baseline Reduction Slice 67

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 67 完成历史，记录本轮继续在 `backend` 的 `DirectModelAgent.kt` 上做的未使用参数收紧。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 170 条降到 168 条。
- `backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt` 清掉了 2 条 `UnusedParameter` baseline。
- backend baseline 里的 `UnusedParameter` 从 6 条降到 4 条，剩余参数清理面继续缩小到 `UserTodoStore.kt`、`WeaviateClient.kt` 与 `WebPageDownloader.kt`。
- 本轮没有改 `DirectModelAgent` 的引用规则、工具暴露、`searchContext()` 行为或 WebSocket 消息合同。

## Completed Slice

1. Slice 67: 删除 `DirectModelAgent.processInput(...)` 上未接线的 `requestUserId` 与 `accessibleSessionIds` 参数，收紧主线 AI 入口签名。
2. Slice 67: 同步删除 `WebSocketConfig.kt` 里为这两个参数预先计算的 `accessibleSessionIds` 死代码块，避免删签名后立刻引入新的 `UnusedPrivateProperty` 噪音。
3. Slice 67: 从 `backend.xml` 移除对应 2 条 `UnusedParameter` baseline，并保持 `DirectModelAgent` 的 system prompt、Claude CLI/API fallback、citation 归一化与回调语义不变。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮说明“删未使用参数”往往要顺着调用点一起看；先收紧签名，再让 detekt 暴露残留死代码，比一开始手工扫所有局部变量更稳。
