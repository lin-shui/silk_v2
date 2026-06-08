# Lint Baseline Reduction Slice 98

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 98 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/SearchDrivenAgent.kt` 上继续收敛 detekt 的 `TooGenericExceptionThrown` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 110 条降到 109 条。
- `SearchDrivenAgent.kt` 中两处 HTTP 非 200 响应已从 `throw Exception(...)` 收紧为 `error(...)`。
- 上层调用方仍按既有 fallback 逻辑处理失败；本轮没有改变搜索、意图分析或流式输出链路。

## Completed Slice

1. Slice 98: 清理 `SearchDrivenAgent.kt` 的 1 条 `TooGenericExceptionThrown` baseline。
2. Slice 98: 保持原有错误文案和失败路径，只把泛型 `Exception` 抛错收紧为 detekt 接受的 `error(...)` 失败出口。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一刀继续沿用“单文件、单规则、小步收敛”的 backend baseline 策略，没有回到 `Routing.kt` 的聚合 broad-catch。
- `SearchDrivenAgent.kt` 仍有 `TooGenericExceptionCaught` / `SwallowedException` / `CyclomaticComplexMethod` / `NestedBlockDepth` 等剩余项；后续若回到此文件，应继续按单规则慢拆。
