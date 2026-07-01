# Lint Baseline Reduction Slice 108

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 108 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/agents/acp/AcpClient.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 90 条降到 89 条。
- `AcpClient.kt` 不再使用 `catch (e: Exception)` 处理 transport send、receive loop、消息分发和 permission request handler 失败。
- ACP receive loop 继续保持“坏消息只影响当前消息，不拖垮整条连接”的既有行为；`CancellationException` 仍然透传。

## Completed Slice

1. Slice 108: 清理 `AcpClient.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 108: 把 ACP 请求发送、JSON 解析和 permission request handler 错误处理统一收敛到 `runCatching`，同时保留 pending 清理与 JSON-RPC error 回包语义。
3. Slice 108: 复用现有 `AcpClientTest` 回归验证 malformed JSON、handler 抛错、timeout 和 permission dispatch，不改变 ACP 客户端对外行为。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.agents.acp.AcpClientTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮选择 `AcpClient.kt` 而不是 `Routing.kt`，是因为 `Routing.kt` 的 broad-catch 仍按文件级聚合，单拆几个路由 catch 还不能独立删 baseline。
- 后续若继续推进 ACP / Agent 框架 lint，优先找 `AcpClient` 邻近的小文件或单职责错误处理点，不把复杂度收敛和异常语义收敛混在同一 slice。
