# Lint Baseline Reduction Slice 113

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 113 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/ClaudeProcessClient.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 85 条降到 84 条。
- `ClaudeProcessClient.streamCompletion()` 不再使用 `catch (e: Exception)`；现改为 `runCatching` 收口失败分支。
- Claude CLI PTY 调用仍保持既有清理合同：`CancellationException` 继续透传，其余失败仍会 `destroyForcibly()` 后重抛。

## Completed Slice

1. Slice 113: 清理 `ClaudeProcessClient.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 113: 保持 Claude CLI 调用失败不会被吞掉，也不改变上层 `DirectModelAgent` 的 Claude CLI -> Anthropic API fallback 逻辑。
3. Slice 113: 用 `:backend:test` 全量回归覆盖现有 AI/Agent/路由链路，确认这次只是在异常收口方式上做行为等价调整。

## Validation

- `./gradlew :backend:test`
- `./gradlew :backend:detekt`

## Notes

- 这轮选择 `ClaudeProcessClient.kt`，是因为它是剩余 `TooGenericExceptionCaught` 里最小的 AI 单职责文件之一，且不需要引入新的协议/模型测试面。
- 后续若继续沿着 AI 面推进，可优先评估 `AnthropicClient.kt` 的同类单 catch 点；如果转向路由异常语义，仍应按单一路由族收窄，不要直接碰 `Routing.kt` 的整文件聚合 baseline。
