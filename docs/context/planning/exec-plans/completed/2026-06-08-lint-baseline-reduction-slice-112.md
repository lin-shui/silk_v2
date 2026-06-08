# Lint Baseline Reduction Slice 112

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 112 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 86 条降到 85 条。
- `UserTodoStore.load()` 不再使用 `catch (e: Exception)`；现改为按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退。
- 损坏的 Todo JSON 仍保持既有 soft-failure 合同：返回空列表，不把读取失败扩散到 `/api/user-todos*` 调用方。

## Completed Slice

1. Slice 112: 清理 `UserTodoStore.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 112: 保持 Todo 本地 JSON 读取失败时“返回空列表”的既有回退，不改变 Todo 路由和生命周期合并入口的返回体合同。
3. Slice 112: 新增 `UserTodoStoreTest` 的损坏 payload 用例，锚定损坏 JSON 仍能被安静跳过。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew :backend:detekt`

## Notes

- 这轮继续沿用“小文件、单规则、单职责”的 backend lint 收敛策略，没有把 `GroupTodoExtractionService.kt` 的复杂度和异常语义一起带进来。
- 后续若继续沿着 Todo 面推进，优先评估 `GroupTodoExtractionService.kt` 中可独立验证的单一异常出口，避免把 LLM 调用、JSON 解析和复杂度收敛混在同一 slice。
