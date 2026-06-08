# Lint Baseline Reduction Slice 114

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 114 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/database/GroupRepository.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 84 条降到 83 条。
- `GroupRepository` 不再使用 `catch (e: Exception)`；SQL 写入失败现按 `ExposedSQLException` / `SQLException` 分层回退，文件系统侧仅保留 `SecurityException`。
- 非关键的 Weaviate 群组同步改为 `runCatching` 收口；`CancellationException` 继续透传，其余失败仍只记 warning，不影响主链路返回合同。

## Completed Slice

1. Slice 114: 清理 `GroupRepository.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 114: 保持群组创建、加人、退群、删群的既有 fallback 语义，不把 Weaviate 同步失败放大成接口失败。
3. Slice 114: 新增 `GroupRepositoryTest` 的重复成员插入用例，锚定数据库唯一键冲突仍按既有合同返回 `false`。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.database.GroupRepositoryTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`

## Notes

- 这轮选择 `GroupRepository.kt`，是因为它没和当前工作树里的其他 backend lint 改动重叠，但能一次清掉一整文件的 broad-catch 基线。
- 后续若继续沿 repository 面推进，可优先看 `Routing.kt` 之外剩余单文件 broad catch；如果转向复杂度收敛，仍应保持单函数慢拆，不把不同规则混进同一 slice。
