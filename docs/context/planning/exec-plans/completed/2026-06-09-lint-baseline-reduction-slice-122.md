# Lint Baseline Reduction Slice 122

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 122 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/agents/core/CommandRouter.kt` 上继续收敛 detekt 的复杂度基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 72 条降到 71 条。
- `CommandRouter.kt` 的总入口 `route()` 已拆成 `/use`、trigger command、`@agent` 和 slash command 几个 helper，不再把所有 agent 路由分支都堆在同一函数里。
- 既有路由合同保持不变：`/agents`、`/use none`、alias 触发、`@unknown` 透传、无当前 agent 时 `PassThrough`，以及 `/session` `/cd` 等 slash command 的返回类型都未改变。

## Completed Slice

1. Slice 122: 清理 `CommandRouter.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 122: 复用现有 `CommandRouterTest` 覆盖 `/use`、trigger command、`@agent` 和 slash command 路由，不新增协议变化。
3. Slice 122: 保持 `AgentRegistry` 驱动的 alias / trigger 解析方式不变，只做结构拆分。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.agents.core.CommandRouterTest`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、单函数、单职责”的 backend lint 收敛方式，没有把 `Routing.kt` / `WebSocketConfig.kt` 这类聚合面混进来。
- `CommandRouter.kt` 已有稳定测试锚点，所以这一步只做纯重构，不补新行为。
