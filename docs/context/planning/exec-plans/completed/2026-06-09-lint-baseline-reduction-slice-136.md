# Lint Baseline Reduction Slice 136

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 136 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 53 条降到 52 条。
- `UserTodoStore.kt` 的 `tryMergeByContainedNormTitle(...)` 已把结构化日程同 key 判断、标题包含判断和字段合并选择拆到 helper，不再把“是否可并 + 如何并”两层逻辑堆在单个函数里。
- 既有标题包含合并合同保持不变：模板/实例仍不会互并，结构化 `alarm/calendar` 仍只在 logical key 一致时合并，普通标题仍按归一化后互为包含触发合并。

## Completed Slice

1. Slice 136: 清理 `UserTodoStore.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 136: 把包含式标题合并拆成 `canMergeStructuredSchedules(...)`、`hasContainedNormalizedTitle(...)`、`selectShorterTitle(...)`、`mergeActionType(...)` 与 `mergeActionDetail(...)` helper，压低主函数复杂度。
3. Slice 136: 在 `UserTodoStoreTest` 补了“包含式标题合并”测试锚点，保持 dedupe、JSON 存储结构和 `/api/user-todos*` 合同不变。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮仍然沿用“同文件、单函数、小步快跑”的 Todo lint 收敛方式，没有把 `updateItem(...)` 这类大聚合入口混进来。
- `UserTodoStore.kt` 现在剩的主要是 `updateItem(...)`、`LoopWithTooManyJumpStatements` 和 `LargeClass`；继续推进时更适合换到别的 backend 单函数点，而不是在同文件硬做大拆。
