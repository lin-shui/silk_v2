# Lint Baseline Reduction Slice 133

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 133 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 56 条降到 55 条。
- `UserTodoStore.kt` 的 `normalizeActionDetailForKey(...)` 已把 exact time、full datetime、relative datetime 解析下沉到 helper，不再把多种时间模式堆在单个函数里。
- 既有 logical key 归一化合同保持不变：能识别的时间字符串仍标准化为固定格式，无法识别或时间非法时仍回退到 `normKey(...)`。

## Completed Slice

1. Slice 133: 清理 `UserTodoStore.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 133: 把 action detail 时间归一化拆成 `parseExactHourMinute(...)`、`parseFullDateTime(...)`、`parseRelativeDateTime(...)` helper，压低主函数复杂度。
3. Slice 133: 保持 Todo dedupe key、JSON 存储结构和 `/api/user-todos*` 合同不变。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## Notes

- 这轮继续沿用“同文件连续收敛”的策略，把 `UserTodoStore.kt` 里低风险的时间解析复杂度点单独摘掉。
- `UserTodoStore.kt` 还剩更大的 merge/update 聚合函数；后续继续推进时，仍应优先拆单函数，不要把生命周期与标题合并规则一起大改。
