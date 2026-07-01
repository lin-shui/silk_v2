# Slice 173

这份归档保留 `lint-baseline-reduction` 的 Slice 173 完成历史，记录本轮在 backend PDF 报告生成器上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `PDFReportGenerator.kt$PDFReportGenerator` 对应的 1 条 `LargeClass` baseline。
- `PDFReportGenerator.kt` 只保留 PDF 生成主流程、文件名规范化和文档生命周期收口。
- 新增 `PDFReportTextSupport.kt`、`PDFReportContentExtractors.kt` 与 `PDFReportSections.kt`，分别承接字体/段落/表格 helper、聊天历史与诊断摘要提取，以及报告章节渲染，保持 PDF 输出结构和下载 URL 合同不变。

## Validation

- `git diff --check`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这轮继续优先收敛低协议风险的 `LargeClass`，避免把 `ChatServer`、`AgentRuntime`、`AIStepwiseAgent` 这类主链迁移混进同一 slice。
- 新 helper 采用顶层函数拆分而不是新增大对象，避免把 class 体积问题转移到另一条新的 `LargeClass` 告警上。
- `silkLint` 首次重跑时被现有 `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseReferences.kt` 的 detekt 新告警阻塞；本轮顺手把 DOM 绑定循环改成 helper + `while`，不改变知识库链接绑定行为。
