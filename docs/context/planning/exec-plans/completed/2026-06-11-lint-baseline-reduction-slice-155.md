# Lint Baseline Reduction Slice 155

这份归档保留 `lint-baseline-reduction` 的 Slice 155 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/AIStepwiseAgent.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 与 `ThrowsCount` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `AIStepwiseAgent.kt$callAIApiStreaming(...)` 对应的 1 条 `CyclomaticComplexMethod` 和 1 条 `ThrowsCount` baseline。
- `callAIApiStreaming(...)` 现在只负责请求主流程编排；请求构造、HTTP 调用、状态码校验、SSE 行读取和单条 `data:` 消费都已下沉到 helper。
- 保持诊断主链合同不变：仍使用同一 API 路径与 timeout，仍保留非 200 即失败、读取超时回退已收集文本、S​​SE `data: [DONE]` 停止流读取，以及逐 chunk 透传前端回调的既有行为。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮继续沿用“同文件、单函数、单职责”的 AIStepwiseAgent 收敛方式，没有把 `processDoctorDiagnosisUpdate(...)` 或文件级 broad-catch 一并混入。
