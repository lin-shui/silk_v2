# Lint Baseline Reduction Slice 144

这份归档保留 `lint-baseline-reduction` 的 Slice 144 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `dedupeDrafts(...)` 拆成“是否参与去重 / logical key 生成 / 候选优先级比较 / long-term template 优先”几个 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$private fun dedupeDrafts(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- 保持模板优先、显式意图优先、`actionDetail` 更长优先和 `MAX_TODOS_PER_REFRESH` 截断的既有去重合同不变。

## 验证

- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮继续保持“单文件、单规则、小步收敛”的节奏，没有顺手处理 `GroupTodoExtractionService.kt` 里剩余的 `heuristicFromSlices(...)`、`extractRecurringTemplateDrafts(...)` 或 broad-catch 基线。
- Todo 提取逻辑仍建议继续优先拆纯本地 helper，暂不把 LLM 调用、JSON 解析和 refresh 总流程混到同一刀。
