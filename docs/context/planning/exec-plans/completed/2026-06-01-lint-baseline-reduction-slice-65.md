# Lint Baseline Reduction Slice 65

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 65 完成历史，记录本轮继续在 `backend` 的 `PDFReportGenerator.kt` 上做的未使用签名与死代码收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 177 条降到 174 条。
- `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 清掉了 1 条 `UnusedParameter` 和 2 条 `UnusedPrivateMember` baseline。
- backend baseline 现在只剩 `UnusedParameter` 6 条、`UnusedPrivateMember` 3 条，剩余静态清理面已明显缩小。
- 本轮没有改 PDF 下载路由、报告内容结构或外部接口。

## Completed Slice

1. Slice 65: 删除 `PDFReportGenerator.kt` 里未使用的 `addExecutionSummary(...)` 与 `formatResultForPDF(...)` helper，以及随之失效的 `successColor()` / `errorColor()` 辅助颜色入口。
2. Slice 65: 去掉 `createMixedFontParagraph(...)` 上游那条未生效的 `englishFont` 透传链，收紧 `addReportHeader(...)`、`addPatientInfo(...)`、`addSummaryReportSection(...)`、`parseAndRenderFormattedText(...)`、`addDisclaimer(...)`、`addReportFooter(...)` 与 `addDiagnosisStepsTable(...)` 的实际签名。
3. Slice 65: 同步删除 `backend.xml` 里对应的 3 条 baseline，并保持 PDF 标题、患者信息、诊断步骤表格、总结报告和免责声明渲染语义不变。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮说明 `UnusedParameter` 常常不是点状问题，而是整条“无效透传链”；后续看到类似的字体/上下文参数时，优先回溯整条调用链一起收口。
