# Lint Baseline Reduction Slice 102

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 102 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/` 下继续收敛 `TooGenericExceptionThrown` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 102 条降到 99 条。
- `AnthropicClient.kt` 的 Anthropic HTTP 非 200 失败出口从 `throw Exception(...)` 收敛为 `error(...)`。
- `AIStepwiseAgent.kt` 的同步/流式 API 非 200 失败出口共 2 处从 `throw Exception(...)` 收敛为 `error(...)`。
- 上层仍保持同样的 `IllegalStateException` 失败语义与 fallback 路径，没有改变 API 错误文案。

## Completed Slice

1. Slice 102: 清理 `AnthropicClient.kt` 的 1 条 `TooGenericExceptionThrown` baseline。
2. Slice 102: 清理 `AIStepwiseAgent.kt` 的 2 条 `TooGenericExceptionThrown` baseline。
3. Slice 102: 把 backend baseline 压到两位数，后续继续优先选可独立验证的小切片推进。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮继续沿用“同一规则、同一模块、小批量收敛”的策略，没有顺带拆 `AIStepwiseAgent.kt` 的复杂度或嵌套深度。
- backend 剩余 `TooGenericExceptionThrown` 现在只剩测试里的 `AcpClientTest.kt` 和 `PDFReportGenerator.kt` 一条主源码项。
