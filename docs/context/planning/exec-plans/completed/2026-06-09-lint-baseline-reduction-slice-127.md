# Lint Baseline Reduction Slice 127

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 127 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 63 条降到 62 条。
- `GroupTodoExtractionService.kt` 中纪念日 yearly template 的 month-day anchor 判定已提成 helper，不再在 `classifyTaskKind()` 里内联展开 month/day 非空与范围检查。
- 既有任务分类合同保持不变：只有合法 `MM-DD` 才会生成 yearly anchor，非法日期仍回退 `null`。

## Completed Slice

1. Slice 127: 清理 `GroupTodoExtractionService.kt` 的 1 条 `ComplexCondition` baseline。
2. Slice 127: 不改 yearly/workday/short-term 的分类顺序，只把 month-day 合法性判断收口到 helper。
3. Slice 127: 通过 `:backend:test` 兜底，确认 Todo 提取、路由和持久化相关既有行为未回归。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续优先消化“独立可减数”的小型 baseline，而不是直接进入 `Routing.kt` 的文件级 broad-catch 聚合点。
- 处理完这两条 `ComplexCondition` 后，backend 剩余 baseline 已不再包含该规则，后续重点更集中在复杂度和 broad-catch。
