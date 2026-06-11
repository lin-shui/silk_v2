# Lint Baseline Reduction Slice 152

这份归档保留 `lint-baseline-reduction` 的 Slice 152 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 上继续收敛 detekt 的 `UnusedPrivateProperty` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `PDFReportGenerator.kt$chineseFontPath` 对应的 1 条 `UnusedPrivateProperty` baseline。
- 移除了未接线的 `chineseFontPath` 懒加载探测逻辑；当前 PDF 生成实际仍走 `createChineseFont()` 的内置 CJK 字体路径，没有改变报告导出主流程。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮只收掉明确未使用的私有属性，没有把 `PDFReportGenerator.kt` 里其它异常语义或大函数问题混进来。
