# Lint Baseline Reduction Slice 159

这份归档保留 `lint-baseline-reduction` 的 Slice 159 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上继续收敛 detekt 的 `LoopWithTooManyJumpStatements` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `UserTodoStore.kt$for` 对应的 1 条 `LoopWithTooManyJumpStatements` baseline。
- `mergeContainedUndoneTitles(...)` 不再依赖双层 `for` + `continue`/`break` 控制流；首个可合并 pair 的查找现已下沉到 `findContainedTitleMerge(...)` helper。
- `instantiateRecurringTemplates(...)` 与 `mergeExtracted(...)` 的循环控制流也一并收敛到 helper，不再依赖多个 `continue` 分支；模板实例化与抽取结果入库现在统一经 `buildRecurringInstanceIfDue(...)`、`buildMergedExtractedItem(...)` 判定。
- 保持既有 Todo 合同不变：仍按首次找到的可合并 pair 逐轮合并，继续保持模板/实例不互并、结构化日程仅同 logical key 合并、归一化标题互为包含时优先保留较短标题与较长 `actionDetail`，并保持“当天已实例化模板不重复生成”“重复 logical key 或非法标题的抽取结果跳过”的既有语义。
- 新增 `UserTodoStoreTest` 窄测试，锚定 `mergeExtracted(...)` 对重复 logical key 与非法标题的过滤行为，避免后续为了压 loop 复杂度误改入库合同。

## 验证

- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮继续留在 Todo 面小步收敛，没有把 `LargeClass` 或 `refreshTodosForUser(...)` 这类重构型条目混进来。
