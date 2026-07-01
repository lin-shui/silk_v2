# Lint Baseline Reduction Slice 118

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 118 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/ToolPolicyManager.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 与 `SwallowedException` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 80 条降到 78 条。
- `ToolPolicyManager.kt` 不再使用 `catch (e: Exception)`；配置文件加载现按 `SerializationException` / `IOException` / `SecurityException` 分层回退，非法权限枚举只回退到 `DISABLED`。
- 路径规范化失败也不再依赖 broad catch；`canonicalPath` 失败时仅在 `IOException` / `SecurityException` 下退回原始路径比较，保持原有沙箱判定合同。

## Completed Slice

1. Slice 118: 清理 `ToolPolicyManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 118: 清理 `ToolPolicyManager.kt` 的 1 条 `SwallowedException` baseline。
3. Slice 118: 新增 `ToolPolicyManagerTest`，锚定沙箱路径拒绝、禁止目录优先级和安全命令校验行为。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.ai.ToolPolicyManagerTest`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮选择 `ToolPolicyManager.kt`，是因为它属于典型的“小文件、单职责、异常语义清晰”的收敛面，适合继续压缩 backend baseline 而不碰消息合同。
- 后续若继续做异常语义，优先仍应选这种可单文件闭环的面；`Routing.kt` / `WebSocketConfig.kt` 这类聚合面继续留到先拆职责再删 baseline。
