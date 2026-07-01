# Lint Baseline Reduction Slice 166

这份归档保留 `lint-baseline-reduction` 的 Slice 166 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 的 `LoopWithTooManyJumpStatements` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$GroupTodoExtractionService$for` 对应的 1 条 `LoopWithTooManyJumpStatements` baseline。
- 历史加载与群切片收集改为 `Sequence` / `mapNotNull` 过滤，保持 `historyBaseDirs` 查找顺序、空历史跳过和最近 `MAX_MESSAGES_PER_GROUP` 条截取语义不变。
- transcript 行展开、启发式候选收集、周期模板候选收集和 draft 去重不再依赖多跳转 `for + continue` 控制流。
- 保持既有 Todo 抽取合同不变：助手消息仍跳过，每条消息仍只接受一次 alarm fallback，周期模板 matched lines 仍按原文本截断收集，draft dedupe 仍保持长期模板、显式意图和更完整 action detail 的优先级。

## 验证

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`

## 备注

- 这轮只处理 `GroupTodoExtractionService` 的 loop-jump 聚合 baseline，没有把 `Routing.kt` 的复杂度/异常聚合、`AIStepwiseAgent.kt` / `AgentRuntime.kt` 的剩余 loop-jump 或 broad-catch 面混进同一 slice。
