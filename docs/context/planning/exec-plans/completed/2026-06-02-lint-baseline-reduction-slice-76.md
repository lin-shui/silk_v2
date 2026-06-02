# Lint Baseline Reduction Slice 76

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 76 完成历史，记录本轮继续在 `backend` 的 `WebSocketConfig.kt` 上做的 URL 处理链路层级收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 151 条降到 150 条。
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 清掉了 `processUrlsInMessage(...)` 上 1 条 `NestedBlockDepth` baseline。
- 本轮没有改 WebSocket 消息合同、URL 下载策略优先级、文件消息 payload 或搜索索引内容结构。

## Completed Slice

1. Slice 76: 把 `processUrlsInMessage(...)` 收敛成“新 URL 筛选 / 单链接处理 / 下载后文件广播 / 搜索索引 / 状态清理”的 orchestration，并把已处理 URL 归一化、文件广播和索引正文构造拆成 helper。
2. Slice 76: 从 `backend.xml` 移除 `WebSocketConfig.kt` 对应的 `NestedBlockDepth` baseline 1 条。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :backend:test --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `processUrlsInMessage(...)` 现在只保留 URL 处理主链编排，下载结果保存、文件消息广播和搜索索引都已经下沉到 helper；后续继续改 URL 入库链路时优先沿这些 helper 扩展，不要把副作用重新堆回主循环。
