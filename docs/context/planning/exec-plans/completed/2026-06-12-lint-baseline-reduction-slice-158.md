# Lint Baseline Reduction Slice 158

这份归档保留 `lint-baseline-reduction` 的 Slice 158 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/pdf/PDFReportGenerator.kt` 上继续收敛 detekt 的异常语义基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `PDFReportGenerator.kt$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` 和 1 条 `SwallowedException` baseline。
- 字体加载、字符间距设置、医生指令/用户症状提取，以及 PDF 主流程失败收尾现统一改为 `runCatching` + helper 收口，不再依赖文件级 `catch (e: Exception)`。
- 保持既有容错合同不变：字体失败仍回退 Helvetica，字符间距失败仍仅退默认值，摘要提取失败仍回退空串或提示文案，PDF 主异常仍包装为 `IllegalStateException` 向上抛出，关闭失败仍只记 warning。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮只处理 `PDFReportGenerator.kt` 的文件级异常语义聚合，没有把 `LargeClass` 或其他 PDF 渲染结构性重构一并混进来。
