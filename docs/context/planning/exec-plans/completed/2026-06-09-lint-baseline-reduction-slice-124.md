# Lint Baseline Reduction Slice 124

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 124 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/SearchDrivenAgent.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 69 条降到 65 条。
- `SearchDrivenAgent.kt` 的离线意图分析已改成规则表匹配，不再把目标、情绪和 help 判定都堆在一个 `when` 链里。
- 三层搜索、意图分析、流式 SSE 解析和索引失败出口现统一用 helper + `runCatching` 收口，并继续透传 `CancellationException`。

## Completed Slice

1. Slice 124: 清理 `SearchDrivenAgent.kt` 的 1 条 `CyclomaticComplexMethod`、1 条 `NestedBlockDepth`、1 条 `SwallowedException` 和 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 124: 保持三层搜索合同不变，`Weaviate` 不可用仍回退 `null`，外部搜索失败仍返回 `source = "error"` 的空结果。
3. Slice 124: 流式读取仍忽略坏 SSE 片段，但现在只按 `SerializationException` / `IllegalArgumentException` 分层跳过，并补 debug 日志。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续避开当前工作树里已在修改的 `Application.kt`、`CommandRouter.kt`、`ToolPolicyManager.kt` 和 `FileRoutes.kt`，优先选择未重叠的 backend 单文件切片。
- `Routing.kt` 的文件级 broad-catch baseline 仍是后续重点，但要继续减那两条聚合签名，还是要先按单一路由族慢拆。
