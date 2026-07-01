# Lint Baseline Reduction Slice 138

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 138 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/ExternalSearchService.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 51 条降到 50 条。
- `ExternalSearchService.kt` 不再依赖 `catch (e: Exception)`；顶层搜索入口、单次 attempt 和各搜索 provider 的失败回退现统一收口到 `recoverSearchFailure(...)` helper。
- 既有搜索合同保持不变：`CancellationException` 仍直接透传；SearXNG -> SerpAPI -> Bing -> Wikipedia -> DuckDuckGo 的尝试顺序与成功短路不变；SerpAPI 失败后仍继续 fallback 到 DuckDuckGo。

## Completed Slice

1. Slice 138: 清理 `ExternalSearchService.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 138: 用 `recoverSearchFailure(...)` 统一 provider 失败收口，保留各 provider 原有的日志级别和失败返回体。
3. Slice 138: 保持外部搜索结果结构、错误文案字段和 timeout/cancellation 语义不变，不引入新的 baseline。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、同类异常语义、小步快跑”的 backend lint 收敛方式，没有把 `Routing.kt` 或 `WeaviateClient.kt` 这类更大的聚合异常面混进来。
- `ExternalSearchService.kt` 当前剩余 detekt 已经不是 broad-catch；如果后续再回到搜索域，更适合考虑 `WeaviateClient.kt` 或 `DirectModelAgent.kt` 的独立切片，而不是重新扩散到多文件。
