# Lint Baseline Reduction Slice 128

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 128 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/AnthropicClient.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 62 条降到 61 条。
- `AnthropicClient.kt` 的 SSE 事件容错现在只吞解析类失败，不再把流式回调或其他运行时错误一起包进 broad catch。
- 既有流式合同保持不变：坏事件仍只记 debug 并跳过，正常 chunk 聚合与回调时机不变，协程取消也不会被吞掉。

## Completed Slice

1. Slice 128: 清理 `AnthropicClient.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 128: 把 `handleEvent(...)` 周围的 `catch (e: Exception)` 收窄为 `SerializationException`、`IllegalArgumentException` 与 `IllegalStateException`，只覆盖 JSON/SSE 解析失败。
3. Slice 128: 保留流式增量回调阈值与残缺 SSE 容错行为，不改 Anthropic 协议转换、tool_use 收集或 citation 聚合合同。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、单规则、单职责”的 backend lint 收敛策略，优先处理能直接减少 baseline 的解析边界点。
- `Routing.kt` / `AgentRuntime.kt` 一类聚合面仍留在 active plan，后续更适合继续按单函数或单路由族慢拆。
