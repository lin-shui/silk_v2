# Lint Baseline Reduction Slice 123

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 123 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/ExternalSearchService.kt` 上继续收敛 detekt 的复杂度基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 71 条降到 69 条。
- `ExternalSearchService.search(...)` 已拆成统一的 attempt 列表和单次执行 helper，不再把五段搜索引擎回退链都堆在一个函数里。
- 既有搜索合同保持不变：仍按 `SearXNG -> SerpAPI -> Bing -> Wikipedia -> DuckDuckGo` 顺序短路返回，所有引擎都失败时仍返回 `source = "none"`，顶层异常仍返回 `source = "error"`。

## Completed Slice

1. Slice 123: 清理 `ExternalSearchService.kt` 的 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline。
2. Slice 123: 保持各搜索引擎的启用条件、成功判定和 warning/info 日志语义不变，只做结构拆分。
3. Slice 123: 顶层和单次尝试都补上 `CancellationException` 透传，避免协程取消被误吞。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮避开了当前工作树里已经在修改的 `Application.kt`、`CommandRouter.kt`、`ToolPolicyManager.kt` 和 `FileRoutes.kt`，优先选择未重叠的 backend 单函数复杂度面。
- `Routing.kt` 的 broad-catch 仍是后续重点，但要真正减少那两条文件级 baseline，仍需要先按单一路由族继续收窄。
