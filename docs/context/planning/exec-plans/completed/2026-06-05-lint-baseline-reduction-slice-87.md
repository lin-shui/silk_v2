# Lint Baseline Reduction Slice 87

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 87 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 上做一组低风险 lint 收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 150 条降到 148 条。
- `WebSocketConfig.kt` 清掉了 Claude Code 拦截前置判断上的 1 条 `ComplexCondition` baseline。
- `WebSocketConfig.kt` 清掉了 1 条 `PrintStackTrace` baseline，并把剩余两处调试栈打印改成结构化 logger 记录。
- 本轮没有改 WebSocket 消息合同、AI 触发条件、历史持久化顺序或 URL 下载行为。

## Completed Slice

1. Slice 87: 把 Claude Code 广播拦截前置条件抽成 `shouldInterceptClaudeCodeBroadcast(...)`，保留原有“仅非 Silk 私聊且仅普通非 Agent 文本才拦截”的语义。
2. Slice 87: 把公共广播与最终 AI 回复发送路径统一到 `sendFrameSafely(...)`，把直接 `printStackTrace()` 改成 logger 记录，避免继续依赖 print-stack-trace baseline。
3. Slice 87: 从 `backend.xml` 移除 `WebSocketConfig.kt` 对应的 `ComplexCondition` 和 `PrintStackTrace` baseline 各 1 条；广义异常 baseline 本轮保持不动，留待后续单独切异常语义 slice。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `WebSocketConfig.kt` 的 `TooGenericExceptionCaught` / `SwallowedException` 在 detekt baseline 中仍以文件级签名聚合；本轮没有继续硬拆，避免把一个小 slice 膨胀成整文件异常治理。
- 后续如果继续切 `WebSocketConfig.kt`，建议把异常语义按“数据库 fallback / WebSocket 发送 / AI 任务 wrapper / 群组查询”分批拆，不要一次性移除整个文件的 broad-catch baseline。
