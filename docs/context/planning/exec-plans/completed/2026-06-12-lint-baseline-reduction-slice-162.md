# Lint Baseline Reduction Slice 162

这份归档保留 `lint-baseline-reduction` 的 Slice 162 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/WeaviateClient.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `WeaviateClient.kt$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- `WeaviateClient` 内 ready/meta 健康检查、session 查询/注册、文档索引、删除与 BM25 搜索失败路径不再依赖散落的 `catch (e: Exception)`；失败恢复现统一经 `recoverWeaviateFailure(...)` helper 收口。
- 保持既有 Weaviate 容错合同不变：连接/查询失败仍只记日志并回退 `false` / 空列表 / 空搜索结果；批量删除仍保持 best-effort，单个 `messageId` 失败不会中断后续删除。
- `recoverWeaviateFailure(...)` 显式透传 `CancellationException`，避免协程取消被 broad-catch 误吞。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮只处理 `WeaviateClient` 的 broad-catch 异常语义，没有把 `Routing.kt` / `WebSocketConfig.kt` 的整文件聚合项混进同一 slice。
