# Lint Baseline Reduction Slice 142

这份归档保留 `lint-baseline-reduction` 的 Slice 142 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/DirectModelAgent.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `normalizeCitedReferences(...)` 拆成提取引用 key、匹配已有元数据、生成占位引用、正文重编号与 key 解析等 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `DirectModelAgent.kt$private fun normalizeCitedReferences(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- 保持 citation / available 的去重顺序、重编号规则、占位标题文案与最终 `FinalCitationResult` 合同不变。

## 验证

- `./gradlew silkLint`
- `./gradlew :backend:test --tests com.silk.backend.ai.DirectModelAgentCitationTest`
- `git diff --check`

## 备注

- 这轮继续维持“单文件、单规则、合同测试锚定”的节奏，没有改工具暴露、web_search 流程或前端消息合同。
- `DirectModelAgent.kt` 仍有 `TooGenericExceptionCaught` 与 `LoopWithTooManyJumpStatements` 等其它基线；后续如果回到 AI 面，继续按单函数慢拆，不把异常语义和大循环重构混成一刀。
