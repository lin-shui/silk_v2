# Lint Baseline Reduction Slice 131

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 131 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/routes/FileRoutes.kt` 上收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 58 条降到 57 条。
- `FileRoutes.kt` 的 `chunkText(...)` 已把 chunk 边界解析下沉到 helper，不再把窗口裁剪、句边界回退和 separator 扫描都堆在同一层循环里。
- 既有文件索引切块合同保持不变：仍然优先按段落/换行/句号切分，找不到边界时回退固定窗口，并继续保留 overlap 续接。

## Completed Slice

1. Slice 131: 清理 `FileRoutes.kt` 的 1 条 `NestedBlockDepth` baseline。
2. Slice 131: 把长文本切块中的边界探测拆为 `resolveChunkEnd(...)` 与 `findChunkBoundary(...)` helper，缩浅主循环嵌套。
3. Slice 131: 保持上传、下载、索引入口和 `ChunkInfo` payload 不变，不改文件路由对外合同。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## Notes

- 这轮刻意避开了当前工作树里已在推进的 `AnthropicClient.kt`，改走未冲突的 `FileRoutes.kt` 单函数收敛，便于独立 review。
- `FileRoutes.kt` 后续若继续推进，优先考虑单函数复杂度或单点异常语义，不建议把整个 `fileRoutes()` 聚合入口一次性大拆。
