# Lint Baseline Reduction Slice 103

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 103 完成历史，记录本轮继续清理 backend 中两条低风险 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 99 条降到 97 条。
- `AIStepwiseAgent.kt` 删除了未使用的 `finalLength` 局部值，不再保留一条 `UnusedPrivateProperty` baseline。
- `PDFReportGenerator.kt` 的失败重抛从 `RuntimeException(...)` 收紧为 `IllegalStateException(...)`，保留原有 message 和 cause。

## Completed Slice

1. Slice 103: 清理 `AIStepwiseAgent.kt` 的 1 条 `UnusedPrivateProperty` baseline。
2. Slice 103: 清理 `PDFReportGenerator.kt` 的 1 条 `TooGenericExceptionThrown` baseline。
3. Slice 103: 保持 AI 流式聚合与 PDF 生成失败传播语义不变，只收紧实现层噪音。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `TooGenericExceptionThrown` 在主源码 backend 已清零；剩余同类项只在测试 `AcpClientTest.kt`。
- backend baseline 已压到 97，后续更值得优先处理 broad-catch 或复杂度热点，而不是继续扫零散轻项。
