# Lint Baseline Reduction Slice 90

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 90 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/WeaviateClient.kt` 上完成一条低风险日志收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 145 条降到 144 条。
- `WeaviateClient.kt` 清掉了 1 条 `PrintStackTrace` baseline。
- 索引异常、混合搜索异常和 BM25 搜索异常现在统一走结构化 logger，保留原有失败返回值与降级语义。
- 本轮没有改 Weaviate API 路径、搜索排序、过滤条件或返回 DTO。

## Completed Slice

1. Slice 90: 把 `WeaviateClient.kt` 中 3 处 `printStackTrace()` 改成结构化 `logger.error(..., e)`。
2. Slice 90: 从 `backend.xml` 移除 `PrintStackTrace:WeaviateClient.kt$WeaviateClient$e` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `WeaviateClient.kt` 的 `TooGenericExceptionCaught` baseline 仍保留；本轮不把网络、序列化和 GraphQL 解析异常细分，以避免把小 slice 膨胀成搜索层错误语义重构。
- 后续若继续切这个文件，优先考虑围绕 `isReady()` 或单个搜索入口拆 broad catch，而不是横跨整个 client 做一轮大面积异常替换。
