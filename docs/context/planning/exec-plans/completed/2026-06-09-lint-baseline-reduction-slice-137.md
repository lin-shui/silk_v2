# Lint Baseline Reduction Slice 137

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 137 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 52 条降到 51 条。
- `GroupTodoExtractionService.kt` 的 `extractRoughHourMinute(...)` 已把中文半点、中文整点、`HH:mm`、阿拉伯数字点钟解析拆到 helper，不再把多种时间模式堆在单个函数里。
- 既有 Todo 启发式时间解析合同保持不变：`下午/晚上/傍晚` 仍走 PM 归一化，`半` 仍解析为 `30` 分，非法 hour/minute 仍回退 `null`。

## Completed Slice

1. Slice 137: 清理 `GroupTodoExtractionService.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 137: 把粗粒度时间提取拆成 `parseChineseHalfHour(...)`、`parseChineseExactHour(...)`、`parseHourMinute(...)`、`parseArabicHour(...)`、`adjustPmHour(...)` 与 `buildHourMinute(...)` helper，压低主函数复杂度。
3. Slice 137: 保持群聊 Todo 启发式兜底、长期模板识别和 `UserTodoStore` 写入合同不变。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮切到同一 Todo 域的 `GroupTodoExtractionService.kt`，继续沿用“单函数、小步快跑”的复杂度收敛方式。
- `GroupTodoExtractionService.kt` 后续若继续推进，更适合优先挑 `dedupeDrafts(...)` 或别的单函数点，不要直接把 `heuristicFromSlices(...)` / `extractRecurringTemplateDrafts(...)` 这类更宽的函数混进同一 slice。
