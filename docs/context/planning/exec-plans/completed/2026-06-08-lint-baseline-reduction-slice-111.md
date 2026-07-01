# Lint Baseline Reduction Slice 111

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 111 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/database/ContactRepository.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 87 条降到 86 条。
- `ContactRepository.kt` 的联系人写入/删除/请求处理失败路径不再使用 `catch (e: Exception)`；现改为仅对 `ExposedSQLException` / `SQLException` 回退。
- 重复联系人关系写入仍保持既有 soft-failure 合同：返回 `false`，不向上抛出异常。

## Completed Slice

1. Slice 111: 清理 `ContactRepository.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 111: 保持联系人创建、删除、请求创建和请求处理在数据库写入失败时的原有回退返回值，不改变路由层返回体合同。
3. Slice 111: 新增 `ContactRepositoryTest`，锚定重复联系人插入仍返回 `false` 的既有行为。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.database.ContactRepositoryTest`
- `./gradlew :backend:detekt`

## Notes

- 这轮继续沿用“小文件、单规则、单职责”的 backend lint 收敛策略，没有把 `GroupRepository.kt` 里混合了 Weaviate 同步与聊天历史目录副作用的 broad-catch 一起带上。
- 后续若继续沿着数据库仓储面推进，优先再评估 `GroupRepository.kt` 中纯 SQL 写路径，避免把外部同步、文件删除和数据库异常收敛混在同一 slice。
