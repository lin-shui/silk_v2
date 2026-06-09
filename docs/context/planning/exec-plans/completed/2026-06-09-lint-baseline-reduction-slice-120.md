# Lint Baseline Reduction Slice 120

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 120 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Application.kt` 上继续收敛 detekt 的复杂度基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 77 条降到 74 条。
- `Application.kt` 的历史聊天批量索引主循环已拆成单职责 helper，不再把会话筛选、游标读取、消息转换、分批索引和 totals 汇总全部塞在同一个函数里。
- 启动期的 Weaviate 历史索引仍保持既有语义：只索引 `group_*` 会话、游标文件仍是 `.weaviate_cursor`、每批 50 条、单会话失败只记 warning 不阻断启动。

## Completed Slice

1. Slice 120: 清理 `Application.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 120: 清理 `Application.kt` 的 1 条 `NestedBlockDepth` baseline。
3. Slice 120: 清理 `Application.kt` 的 1 条 `LoopWithTooManyJumpStatements` baseline。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“小文件、单函数、单职责”的 backend lint 收敛策略，没有把 `Routing.kt` / `WebSocketConfig.kt` 这类文件级聚合面混进来。
- 由于 `Application.kt` 已在 Slice 117 做过异常语义收敛，这一轮只做结构性拆分，不改变启动入口、日志口径或索引存储路径。
