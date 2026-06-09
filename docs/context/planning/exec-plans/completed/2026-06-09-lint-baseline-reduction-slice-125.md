# Lint Baseline Reduction Slice 125

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 125 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/agents/core/AcpUpdateMapper.kt` 上继续收敛 detekt 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 65 条降到 64 条。
- `AcpUpdateMapper.map()` 已改成按 `sessionUpdate` 类型分发到单独 helper，不再把 message/thought/tool/plan/question 映射都堆在一个函数里。
- Ask-user-question 卡片、streaming 累积文本和 tool/thought 的 stableId 合同保持不变。

## Completed Slice

1. Slice 125: 清理 `AcpUpdateMapper.kt` 的 1 条 `CyclomaticComplexMethod` baseline。
2. Slice 125: 复用现有 `AcpUpdateMapperTest`，保证 `agent_message_chunk`、`tool_call`、`plan` 和 `ask_user_question` 映射行为不变。
3. Slice 125: 把问题卡片解析拆成 `StructuredQuestion` / `QuestionOption` helper，不改 `requestId`、问题顺序或 fallback 规则。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.agents.core.AcpUpdateMapperTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮优先选择了“单函数复杂度” slice，而没有直接进入 `Routing.kt` 的文件级 broad-catch，因为后者即便改善代码，也未必能立刻减少 baseline 数量。
- `Routing.kt` 仍是后续重点，但更适合在 baseline 总量继续下降后，再按单一路由族慢拆异常语义。
