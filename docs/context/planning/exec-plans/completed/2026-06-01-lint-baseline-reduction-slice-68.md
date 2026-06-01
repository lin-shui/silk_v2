# Lint Baseline Reduction Slice 68

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 68 完成历史，记录本轮继续在 `backend` 的 `UserTodoStore.kt`、`WeaviateClient.kt`、`SearchDrivenAgent.kt` 与 `SilkAgent.kt` 上做的未使用参数收紧。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 168 条降到 165 条。
- `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 清掉了 1 条 `UnusedParameter` baseline。
- `backend/src/main/kotlin/com/silk/backend/search/WeaviateClient.kt` 清掉了 2 条 `UnusedParameter` baseline，并把 Weaviate 搜索 helper 收紧到当前真实使用的 session-based 参数。
- backend baseline 里的 `UnusedParameter` 从 4 条降到 1 条，剩余参数清理面只剩 `WebPageDownloader.kt`。
- 本轮没有改 Weaviate 查询结果格式、聊天消息合同、Todo 持久化结构或 WebSocket 路由。

## Completed Slice

1. Slice 68: 删除 `UserTodoStore.normalizeActionDetailForKey(...)` 上未接线的 `actionType` 参数，并保持逻辑去重 key 的时间归一化语义不变。
2. Slice 68: 删除 `WeaviateClient.isolatedSearch(...)` 及其 `foreground/background` helper 链上未实际参与查询的 `userId` / `alpha` 参数，保持搜索仍按当前 session 与跨 session 分层执行。
3. Slice 68: 同步删除 `SearchDrivenAgent` 与 `SilkAgent.generateSearchDrivenResponse(...)` 上只为透传这些死参数而保留的调用链噪音，并从 `backend.xml` 移除对应 3 条 `UnusedParameter` baseline。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮说明“删未使用参数”经常会顺带暴露上一层只为透传而保留的死字段/死签名；顺着调用链继续收一层，比留下假语义参数更稳。
