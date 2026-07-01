# Lint Baseline Reduction Slice 146

这份归档保留 `lint-baseline-reduction` 的 Slice 146 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `extractRecurringTemplateDrafts(...)` 拆成“逐群收集 / 单行判定 / draft 构造 / 标题与时间格式化”几个 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$private fun extractRecurringTemplateDrafts(...)` 对应的 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline。
- 保持工作日习惯 / 纪念日的识别条件、matched lines 截断、`long_term_template` 合同、`workday`/`yearly` repeat rule 与时间锚点语义不变。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## 备注

- 这轮继续沿用同文件、单函数、小步收敛的节奏，没有顺手改 `refreshTodosForUser(...)`、`parseCompactTodoJson(...)` 或 broad-catch 基线。
- `GroupTodoExtractionService.kt` 剩余更适合继续拆的是 `heuristicFromSlices(...)`、`buildTranscriptString(...)` 或 `parseCompactTodoJson(...)`，不建议下一刀直接碰刷新主流程。
