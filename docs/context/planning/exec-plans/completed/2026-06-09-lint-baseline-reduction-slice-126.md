# Lint Baseline Reduction Slice 126

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 126 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 64 条降到 63 条。
- `UserTodoStore.kt` 中 alarm/calendar 结构化任务的类型判定已收口到 helper，不再在包含式合并里重复展开 `alarm || calendar` 条件。
- 既有 Todo 去重合同保持不变：同类结构化日程仍按 logical key 去重，不同时间的结构化提醒仍不会在标题包含时被误并。

## Completed Slice

1. Slice 126: 清理 `UserTodoStore.kt` 的 1 条 `ComplexCondition` baseline。
2. Slice 126: 保持 `mergeItemsByLogicalKey` 与 `tryMergeByContainedNormTitle` 的分工不变，只收敛结构化任务类型判定。
3. Slice 126: 复用现有 `UserTodoStoreTest`，继续锁定 alarm 去重、模板实例化和损坏 payload 容错行为。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮选择的是单条 `ComplexCondition`，优先快速压低 baseline 总量，没有引入新的 Todo 数据结构或 JSON store 变化。
- `Routing.kt` 的文件级异常处理聚合仍保留在后续 slice；等这类“独立可减数”的小点再收一批后，再回头慢拆那组路由更划算。
