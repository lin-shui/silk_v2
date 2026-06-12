# Lint Baseline Reduction Slice 164

这份归档保留 `lint-baseline-reduction` 的 Slice 164 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/AIStepwiseAgent.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `AIStepwiseAgent.kt$AIStepwiseAgent$suspend fun processDoctorDiagnosisUpdate(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- `processDoctorDiagnosisUpdate(...)` 现已拆成“历史消息过滤 / 新消息上下文 / 旧诊断摘要 / 增量流式回写 / stepResults 与 PDF 消息构造” helper，主流程只保留医生诊断更新的编排。
- 保持既有更新诊断合同不变：仍只基于上次诊断之后的新消息构造增量上下文，仍按“每 3 行或 2 秒”门槛向前端推流式增量，最终也仍把“医生医嘱 + 之前诊断 + AI 综合更新”整合后生成 PDF 并保存诊断记录。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮只处理 `AIStepwiseAgent` 的单函数复杂度，没有把同类里剩余的 broad-catch / swallow / loop-jump，或 `Routing.kt` / `GroupTodoExtractionService.kt` 的大聚合项混进同一 slice。
