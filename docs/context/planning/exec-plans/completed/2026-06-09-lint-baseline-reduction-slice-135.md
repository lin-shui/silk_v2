# Lint Baseline Reduction Slice 135

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 135 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 54 条降到 53 条。
- `UserTodoStore.kt` 的 `mergeShortInstanceByState(...)` 已把索引查找、新建 instance、active merge、reopen 判定与 reopen copy 拆到 helper，不再把短期任务的整套状态分支堆在单个函数里。
- 既有短期任务状态合同保持不变：`active` 仍直接合并证据，`done`/`deferred` 仍要求更新证据时间后重开，`cancelled` 仍要求显式意图且证据更新后才重开。

## Completed Slice

1. Slice 135: 清理 `UserTodoStore.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 135: 把短期任务状态 merge 拆成 `findLatestShortInstanceIndex(...)`、`createShortInstanceItem(...)`、`normalizeLifecycleState(...)`、`canReopenShortInstance(...)`、`mergeIntoActiveShortInstance(...)` 与 `reopenShortInstance(...)` helper，压低主函数复杂度。
3. Slice 135: 保持 Todo dedupe key、JSON 存储结构和 `/api/user-todos*` 合同不变。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“同文件、单函数、小步快跑”的 Todo lint 收敛方式，没有把 `updateItem(...)` 这类聚合更新入口混进来。
- `UserTodoStore.kt` 后续若继续推进，剩余更像是 `tryMergeByContainedNormTitle(...)` 或转去别的 backend 单函数复杂度点；不建议现在就硬拆 `LargeClass`。
