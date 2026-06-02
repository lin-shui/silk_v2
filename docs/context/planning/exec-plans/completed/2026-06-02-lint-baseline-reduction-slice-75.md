# Lint Baseline Reduction Slice 75

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 75 完成历史，记录本轮在 `backend` 的 `WebSocketConfig.kt` 上做的 `broadcast(...)` 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 153 条降到 151 条。
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 清掉了 `broadcast(...)` 上 1 条 `CyclomaticComplexMethod` baseline 和 1 条 `NestedBlockDepth` baseline。
- 本轮没有改 WebSocket 消息合同、CC / Silk 路由优先级、消息持久化顺序或前端可见 payload。

## Completed Slice

1. Slice 75: 把 `broadcast(...)` 收敛成“停生成 / 卡片回复 / 去重 / 持久化与索引 / 广播 / URL 异步 / CC 拦截 / Silk AI 分发”的 orchestration，并把卡片回复、Weaviate 索引、CC 单用户广播和 Silk AI 分支拆成 helper。
2. Slice 75: 从 `backend.xml` 移除 `WebSocketConfig.kt` 对应的 `CyclomaticComplexMethod` 和 `NestedBlockDepth` baseline 各 1 条。

## Validation

- `./gradlew :backend:compileKotlin :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :backend:test --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `broadcast(...)` 现在只保留主链编排，卡片回复、持久化/索引、CC 拦截和 Silk AI 分支都已经下沉到 helper；后续继续改这个入口时优先沿这些 helper 扩展，不要再把多条副作用链重新堆回主函数。
