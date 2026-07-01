# Lint Baseline Reduction Slice 71

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 71 完成历史，记录本轮继续在 `backend` 的 `PDFReportGenerator.kt` 上做的总结报告渲染 skip-guard 收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 159 条降到 158 条。
- `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 清掉了 `parseAndRenderFormattedText(...)` 上 1 条 `ComplexCondition` baseline。
- backend 仍然主要剩下复杂度、异常语义和命名类问题；`PDFReportGenerator.kt` 里这类孤立布尔链又少了一处。
- 本轮没有改 PDF 排版输出、标题层级语义或免责声明内容。

## Completed Slice

1. Slice 71: 把 `parseAndRenderFormattedText(...)` 里“跳过空行/分隔线/Agent 签名”的布尔链收敛到 `shouldSkipFormattedSummaryLine(...)` helper，保持渲染过滤行为不变。
2. Slice 71: 从 `backend.xml` 移除对应 1 条 `ComplexCondition` baseline。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这类“总结页渲染前的过滤条件”如果继续堆在主循环里，很快会重新长回复杂条件；优先抽成命名清晰的 helper 比在分支里继续塞词条更稳。
