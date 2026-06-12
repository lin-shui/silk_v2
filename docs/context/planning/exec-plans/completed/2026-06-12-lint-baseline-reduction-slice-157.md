# Lint Baseline Reduction Slice 157

这份归档保留 `lint-baseline-reduction` 的 Slice 157 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `PDFReportGenerator.kt$closeEx: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- PDF 生成主流程失败后的文档关闭清理，改为 `runCatching` 收口，不再依赖 `catch (closeEx: Exception)`。
- 保持原有失败合同不变：主异常仍包装成 `IllegalStateException` 向上抛出，关闭失败仍只记 warning，不覆盖原始 PDF 生成错误。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮只处理一个单点收尾异常，没有把 `PDFReportGenerator.kt` 里其他文件级 `e: Exception` 聚合 baseline、`SwallowedException` 或 `LargeClass` 一并混入。
