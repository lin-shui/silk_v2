# Lint Baseline Reduction Slice 167

这份归档保留 `lint-baseline-reduction` 的 Slice 167 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/agents/core/AgentRuntime.kt` 上继续收敛 detekt 的 `LoopWithTooManyJumpStatements` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `AgentRuntime.kt$AgentRuntime$for` 对应的 1 条 `LoopWithTooManyJumpStatements` baseline。
- `handleAgentDisconnect(...)` 现在先按 user 过滤上下文，再把单个 session 的断线清理交给 `handleDisconnectedSession(...)`，不再依赖 `for + continue` 跳过非目标上下文。
- pending question 的 CARD reply handler 注销统一到 `clearPendingQuestion(...)`，并复用于 `cleanupState(...)` 和 `clearForTest()`。
- 保持既有 Agent 断线清理合同不变：先 cleanup ACP session handlers，再注销 pending question 卡片；运行中的任务仍会 cancel、清空 prompt job、复位 running/cancelled/pendingQuestion，并最终将 `acpSessionId` 置空。

## 验证

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`

## 备注

- 这轮只处理 `AgentRuntime` 的 bridge disconnect loop-jump baseline，没有把同文件 broad-catch、`AIStepwiseAgent` 的剩余 loop/catch，或 `Routing.kt` 的复杂度/异常聚合混进同一 slice。
