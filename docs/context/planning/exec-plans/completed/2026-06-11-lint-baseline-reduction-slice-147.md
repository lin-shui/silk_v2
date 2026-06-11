# Lint Baseline Reduction Slice 147

这份归档保留 `lint-baseline-reduction` 的 Slice 147 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `heuristicFromSlices(...)` 拆成“逐群收集 / 单行候选识别 / 单条 draft 构造 / alarm fallback 状态控制”几个 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$private fun heuristicFromSlices(...)` 对应的 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline。
- 保持 checklist 提取、alarm 文本弱兜底、同一消息只接受一次非 checklist alarm fallback、标题截断、`alarm`/`none` 判定与显式意图推断语义不变。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## 备注

- 这轮继续留在 Todo 面同文件、小步收敛，没有改 `refreshTodosForUser(...)`、`parseCompactTodoJson(...)` 或 broad-catch 基线。
- `GroupTodoExtractionService.kt` 现在剩余更适合继续处理的是 `buildTranscriptString(...)`、`parseCompactTodoJson(...)` 或异常语义；仍不建议下一刀直接碰刷新主流程。
