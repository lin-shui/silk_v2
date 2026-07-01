# Lint Baseline Reduction Slice 115

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 115 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ChatHistoryBackupManager.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 83 条降到 82 条。
- `ChatHistoryBackupManager` 不再使用 `catch (e: Exception)`；备份读写、元数据解析、恢复和清理现按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退。
- 损坏的备份元数据仍保持既有 soft-failure 合同：`listBackups()` 跳过坏文件，`restoreFromBackup()` 返回 `false` 而不是把异常扩散给调用方。

## Completed Slice

1. Slice 115: 清理 `ChatHistoryBackupManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 115: 保持群组删除、消息撤回和历史恢复链路的容错语义，不把单个坏备份文件放大成全量失败。
3. Slice 115: 新增 `ChatHistoryBackupManagerTest`，锚定坏元数据会被列表跳过，且恢复时按既有合同返回 `false`。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.ChatHistoryBackupManagerTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`

## Notes

- 这轮选择 `ChatHistoryBackupManager.kt`，是因为它仍是单文件聚合的 broad-catch 面，但行为边界清晰，适合继续按“小文件、单规则、补测试锚点”的方式推进。
- 后续若继续沿异常语义推进，可优先评估 `AsrRoutes.kt` 或 `Routing.kt` 的单一路由族；如果转向复杂度，则仍应保持单函数慢拆，不把两类规则混在同一 slice。
