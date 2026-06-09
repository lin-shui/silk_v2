# Lint Baseline Reduction Slice 132

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 132 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 57 条降到 56 条。
- `UserTodoStore.kt` 的 `isTemplateDueToday(...)` 已把 active window、yearly anchor、monthly anchor 判断下沉到 helper，不再把日期窗口和周期规则堆在同一分支里。
- 既有 Todo 模板触发合同保持不变：`workday` 仍走 `HolidayCalendarCn`，`yearly` 仍匹配 `MM-DD`，`monthly` 仍匹配 day-of-month，`activeFrom/activeTo` 仍优先过滤。

## Completed Slice

1. Slice 132: 清理 `UserTodoStore.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 132: 把模板今日触发判断拆成 `isWithinActiveWindow(...)`、`matchesYearlyAnchor(...)`、`matchesMonthlyAnchor(...)` helper，压低主函数复杂度。
3. Slice 132: 保持 Todo JSON 结构、生命周期合并和 `/api/user-todos*` 合同不变，不改 roadmap。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、单函数、小步快跑”的 backend lint 收敛方式，没有把 `updateItem(...)` 这类更大的 Todo 聚合函数混进同一 slice。
- `UserTodoStore.kt` 后续若继续推进，更适合继续挑单函数复杂度点；避免把生命周期 merge 与结构化时间解析一起做成大重构。
