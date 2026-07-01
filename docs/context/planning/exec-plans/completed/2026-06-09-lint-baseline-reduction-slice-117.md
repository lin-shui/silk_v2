# Lint Baseline Reduction Slice 117

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 117 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Application.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 81 条降到 80 条。
- `Application.kt` 不再使用 `catch (e: Exception)`；启动期的 Weaviate 群组同步、历史批量索引和单会话索引失败现统一用 `runCatching` 收口，继续保持“记录 warning 并跳过失败项”的既有启动语义。
- 历史聊天批量索引仍保留原有游标推进和跳过空内容/空消息列表的行为，没有改动启动入口、索引合同或存储路径。

## Completed Slice

1. Slice 117: 清理 `Application.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 117: 保持启动阶段的 best-effort 容错语义，单个群组或单个历史会话失败不会中断后端启动。
3. Slice 117: 顺手去掉 `Application.kt` 中一个无意义的 Elvis 分支，避免新增编译 warning。

## Validation

- `./gradlew :backend:detekt :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮选择 `Application.kt`，是因为它虽然仍属 backend 主入口，但 broad-catch 面积小、职责边界清晰，适合在不碰消息合同的前提下继续压缩 baseline。
- 下一步若继续收敛异常语义，仍应优先挑单文件或单职责边界；`WebSocketConfig.kt`/`Routing.kt` 这类文件级聚合点要先拆小面，再删 baseline。
