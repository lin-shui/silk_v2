# Lint Baseline Reduction Slice 94

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 94 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/ExternalSearchService.kt` 上继续收敛静态 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 138 条降到 132 条。
- `ExternalSearchService.kt` 的静态搜索 URL 常量已改为 `const val`。
- 未被引用且只返回常量的 `isAvailable()` 已删除，避免继续保留无意义 API。
- 本轮没有改变搜索优先级、超时策略、请求参数、结果映射或失败回退顺序。

## Completed Slice

1. Slice 94: 把 `ExternalSearchService.kt` 中 5 个可静态化的 URL 常量改成 `const val`，移除对应 `MayBeConst` baseline。
2. Slice 94: 删除未使用的 `isAvailable()`，移除对应 `FunctionOnlyReturningConstant` baseline。
3. Slice 94: 保持搜索行为不变，仅做源码静态化和无用 API 清理。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮避开了 `Routing.kt` 的聚合 broad-catch 签名，优先拿下可独立删除 baseline 的后端小点。
- 下一步如果回到 `Routing.kt`，仍应按单函数、单职责切 slice，不要把整文件的 `TooGenericExceptionCaught` 一次性铺开。
