# Lint Baseline Reduction Slice 161

这份归档保留 `lint-baseline-reduction` 的 Slice 161 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt` 上继续收敛 detekt 的 `LoopWithTooManyJumpStatements` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `DirectModelAgent.kt$for` 对应的 1 条 `LoopWithTooManyJumpStatements` baseline。
- `normalizeCitedReferences(...)` 不再依赖补占位引用时的 `for + continue`；缺失引用的 placeholder 生成现已下沉到 `createPlaceholderReferences(...)` helper。
- 保持既有 citation 合同不变：真实已注册引用仍优先按出现顺序重编号，缺失引用仍会在 `normalizeCitedReferences(...)` 阶段补占位 `MessageReference`，不改变 `citation` / `available` 的索引分桶和前端 sources 列表语义。
- `DirectModelAgentCitationTest` 新增 placeholder 场景，锚定“缺失引用在归一化阶段补占位，且排在已注册引用之后”的既有行为。

## 验证

- `./gradlew :backend:test --tests com.silk.backend.ai.DirectModelAgentCitationTest`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮只处理引用归一化的局部 loop，没有把 `DirectModelAgent` 的 broad-catch、工具策略或搜索流程混入同一 slice。
