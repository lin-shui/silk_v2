# Lint Baseline Reduction Slice 70

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 70 完成历史，记录本轮继续在 `backend` 的 `PDFReportGenerator.kt` 上做的诊断摘要提取复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 163 条降到 159 条。
- `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 清掉了 `extractDiagnosisSummary(...)` 上 1 条 `CyclomaticComplexMethod`、2 条 `ComplexCondition` 和 1 条 `UnusedPrivateProperty` baseline。
- backend baseline 里的 `CyclomaticComplexMethod` 从 33 条降到 32 条；`PDFReportGenerator.kt` 上“诊断摘要提取”这一块不再占用复杂度配额。
- 本轮没有改 PDF 文件结构、导出入口、聊天消息合同或诊断结果文本来源。

## Completed Slice

1. Slice 70: 把 `extractDiagnosisSummary(...)` 拆成“章节识别、条目筛选、摘要拼装”几个 helper，保留西医/中医摘要优先、失败时回退到前 150 字的原有行为。
2. Slice 70: 删除同函数中未接线的 `summary` 临时变量，并从 `backend.xml` 移除对应 4 条 detekt baseline。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 诊断文本解析这类复杂度问题，稳定做法不是继续堆 `when`，而是把“状态切换”和“条目纳入条件”拆开；这样既减复杂度，也更容易后续补规则词。
