# Lint Baseline Reduction Slice 95

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 95 完成历史，记录本轮在若干 backend 单点校验入口上继续收敛 detekt 的 `UseCheckOrError` / `UseRequire` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 132 条降到 126 条。
- `AIConfig.kt` 中 3 个配置前置校验改为 `check(...)`，行为保持为“缺配置即失败并给出明确提示”。
- `AcpWebSocketTransport.kt` 改用 `check(!isClosed)` 守护关闭态发送。
- `SilkAgent.kt` 新增 `requireStepwiseAgent()`，把旧的 `IllegalStateException("Agent not initialized")` 收敛为 `checkNotNull(...)`。
- `AnthropicClient.kt` 把 API key 前置校验改为 `require(...)`，失败文案不变。

## Completed Slice

1. Slice 95: 清理 `AIConfig.kt` 的 3 条 `UseCheckOrError` baseline。
2. Slice 95: 清理 `AcpWebSocketTransport.kt` 的 1 条 `UseCheckOrError` baseline。
3. Slice 95: 清理 `SilkAgent.kt` 的 1 条 `UseCheckOrError` baseline，并抽出复用的 stepwise agent 取值入口。
4. Slice 95: 清理 `AnthropicClient.kt` 的 1 条 `UseRequire` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮只收前置校验语义，不改变配置读取、WS 发送、stepwise agent 初始化时机或 Anthropic 请求链路。
- 下一步继续做 backend baseline 时，仍优先找能独立删除的单文件/单规则切片；`Routing.kt` 的 broad-catch 仍建议单函数慢拆。
