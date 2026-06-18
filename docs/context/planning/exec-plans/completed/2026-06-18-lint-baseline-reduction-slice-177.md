# Slice 177

这份归档保留 `lint-baseline-reduction` 的 Slice 177 完成历史，记录本轮在 backend `AIStepwiseAgent` 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `AIStepwiseAgent.kt$AIStepwiseAgent` 对应的 1 条 `LargeClass` baseline。
- `AIStepwiseAgent.kt` 现在只保留诊断主流程、AI 调用入口、医生增量诊断编排与对外数据模型。
- 新增 `AIStepwiseExecutionSupport.kt`、`AIStepwiseContextSupport.kt`、`AIStepwisePersistence.kt`、`AIStepwiseReportSupport.kt` 与 `AIStepwiseStreamingSupport.kt`，分别承接步骤失败收口、聊天上下文/离线结果、诊断历史持久化、fallback 报告拼装，以及 streaming/quick-response helper，保持诊断步骤顺序、PDF 生成入口、诊断历史文件结构与流式增量回调语义不变。

## Validation

- `git diff --check`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`

## Notes

- 这轮继续避开已脏的 `WebSocketConfig.kt`，优先处理未改动的 backend `LargeClass` 面，减少与现有工作区修改冲突。
- 拆分仍采用顶层 helper 文件，没有把体积问题平移成新的大类；active plan 现在只剩 `AgentRuntime` 与 `ChatServer` 两条 `LargeClass` baseline。
