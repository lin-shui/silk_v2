# Lint Baseline Reduction Slice 153

这份归档保留 `lint-baseline-reduction` 的 Slice 153 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `DirectModelAgent.kt$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- `chat(...)` 与 Claude CLI fallback 入口改为 `runCatching` 收口；正常 `CancellationException` 继续透传，其它失败仍会记录日志、给前端回写错误消息，并在可用时回退到 Anthropic API 路径。
- 保持工具上下文、`chat_history.md` 写入、最终回复落库和 citation 后处理合同不变，没有把 `DirectModelAgent` 的其它复杂度项混进这一 slice。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮优先收掉 backend 中较独立的 broad-catch 文件，没有直接切入 `Routing.kt` 或 `WebSocketConfig.kt` 的聚合 catch 面。
