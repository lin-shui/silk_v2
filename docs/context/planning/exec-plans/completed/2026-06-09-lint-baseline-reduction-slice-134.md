# Lint Baseline Reduction Slice 134

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 134 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 55 条降到 54 条。
- `UserTodoStore.kt` 的 `extractTimeFromTitle(...)` 已把中文半点、中文整点、阿拉伯数字点钟、`HH:mm` 解析拆到 helper，不再把多类时间模式堆在单个函数里。
- 既有标题时间提取合同保持不变：`下午/晚上/傍晚` 仍会做 PM 归一化，`半` 仍解析为 `:30`，非法时间仍回退 `null`。

## Completed Slice

1. Slice 134: 清理 `UserTodoStore.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 134: 把标题时间提取拆成 `parseChineseHalfHour(...)`、`parseChineseExactHour(...)`、`parseArabicHour(...)`、`parseHourMinute(...)` 与共享的 hour/minute helper，压低主函数复杂度。
3. Slice 134: 保持 Todo logical key、JSON 存储结构和 `/api/user-todos*` 合同不变。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“同文件、单函数、小步快跑”的 Todo lint 收敛方式，没有把 `updateItem(...)` 或合并逻辑一起卷进来。
- `UserTodoStore.kt` 后续若继续推进，优先仍应挑单函数复杂度点，避免把生命周期更新和 dedupe merge 混成一次大改。
