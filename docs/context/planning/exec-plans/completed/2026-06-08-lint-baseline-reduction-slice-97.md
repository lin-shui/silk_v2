# Lint Baseline Reduction Slice 97

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 97 完成历史，记录本轮在 backend 剩余协议/模型字段上继续收敛 detekt 的 `ConstructorParameterNaming` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 117 条降到 110 条。
- `AiModels.kt` 的 `tool_calls`、`tool_call_id`、`reasoning_content` 已改成 `camelCase` Kotlin 属性，并通过 `@SerialName(...)` 保持消息合同不变。
- `AIStepwiseAgent.kt`、`GroupTodoExtractionService.kt` 的 `max_tokens` 与 `AIStepwiseAgent.kt` 的 `finish_reason` 已改成 `camelCase + @SerialName(...)`，不改变对上游 API 的请求/响应字段。
- `AcpCapabilities.kt` 的 `_silk` 扩展能力声明已改成 `silkExtensions` 属性，并通过 `@SerialName("_silk")` 保持 ACP initialize 协议字段不变。

## Completed Slice

1. Slice 97: 清理 `AiModels.kt` 的 3 条 `ConstructorParameterNaming` baseline。
2. Slice 97: 清理 `AIStepwiseAgent.kt` 的 2 条 `ConstructorParameterNaming` baseline。
3. Slice 97: 清理 `GroupTodoExtractionService.kt` 的 1 条 `ConstructorParameterNaming` baseline。
4. Slice 97: 清理 `AcpCapabilities.kt` 的 1 条 `ConstructorParameterNaming` baseline。
5. Slice 97: 同步更新 Anthropic、搜索代理、Todo 抽取与 ACP 能力模型的内部字段引用，保持协议键和值语义不变。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一刀继续沿用“单规则、小批量、保合同”的 backend baseline 收敛方式，没有引入新的 detekt baseline。
- backend 剩余 `ConstructorParameterNaming` 已清零；下一步若继续做命名类问题，需回到别的规则面，不再有同类 easy win。
