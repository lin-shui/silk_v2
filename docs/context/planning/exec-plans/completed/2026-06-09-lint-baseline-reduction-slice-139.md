# Lint Baseline Reduction Slice 139

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 139 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ChatHistoryManager.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 50 条降到 49 条。
- `ChatHistoryManager.kt` 不再依赖 `catch (e: Exception)`；会话/历史读写、损坏文件备份与原子写入清理现在按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层处理。
- 既有持久化合同保持不变：损坏历史/会话文件仍会先备份，再拒绝覆盖；保存失败仍只记日志，不改变调用方流程。

## Completed Slice

1. Slice 139: 清理 `ChatHistoryManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 139: 把 session/history 的 decode 与文件写入失败按 JSON 解析、I/O、权限错误分层，不再机械依赖 broad catch。
3. Slice 139: 继续保持“损坏文件备份 + 拒绝覆盖既有历史”的恢复语义，不新增 baseline，也不改消息/会话文件结构。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、同类异常语义、小步快跑”的 backend lint 收敛方式，没有把 `Routing.kt` / `WebSocketConfig.kt` 的文件级聚合异常面混进来。
- `ChatHistoryManager.kt` 现在剩余主要是 `LoopWithTooManyJumpStatements`；如果后续再回到这个文件，更适合单独拆 AI reply 查找循环，而不是再把持久化逻辑和循环复杂度混在同一 slice。
