# Lint Baseline Reduction Slice 165

这份归档保留 `lint-baseline-reduction` 的 Slice 165 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$GroupTodoExtractionService$suspend fun refreshTodosForUser(userId: String)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- `refreshTodosForUser(...)` 现已拆成“准备刷新上下文 / 选择 primary drafts / 应用抽取结果 / 记录 diagnostics” helper，主流程只保留刷新编排。
- 保持既有刷新合同不变：无 API key、无群组、无群消息文件时仍走原来的 skip + diagnostics 路径；LLM 与启发式仍保持二选一，forced recurring 仍会叠加；成功刷新后也仍继续执行 `replaceUndoneWithExtracted(...)`、LLM compact 与 logical-key dedupe 收尾。

## 验证

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## 备注

- 这轮只处理 `GroupTodoExtractionService` 的单函数复杂度，没有把同文件剩余的 loop-jump、`Routing.kt` 的复杂度/异常聚合，或 `AIStepwiseAgent.kt` / `AgentRuntime.kt` 的 broad-catch 面混进同一 slice。
