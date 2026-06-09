# Lint Baseline Reduction Slice 129

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 129 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/ai/AnthropicClient.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 61 条降到 59 条。
- `AnthropicClient.kt` 的 SSE 事件分发已拆成 `content_block_start` / `content_block_delta` / `content_block_stop` helper，不再把 block 类型分派、引用提取和 tool-use 收口都堆在 `handleEvent()`。
- 既有 Anthropic 流式合同保持不变：tool/citation 聚合、坏事件 best-effort 跳过与增量 chunk 回调阈值都未改变。

## Completed Slice

1. Slice 129: 清理 `AnthropicClient.kt` 的 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline。
2. Slice 129: 把 web-search citation 提取、tool-use stop 收口与 delta 文本拼接拆成 helper，避免在同一函数内层层分支。
3. Slice 129: 保持 Anthropic 消息协议、tool-use 参数修复和 citation fallback 逻辑不变，不改外部 payload 或上层 callback 语义。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## Notes

- 这轮继续沿用“同文件连续收敛”的策略，减少在 backend 大聚合面之间来回切换。
- `AnthropicClient.kt` 仍剩一条 `convertMessage(...)` 的复杂度 baseline；后续若继续切这一文件，更适合单独作为下一个复杂度 slice 处理。
