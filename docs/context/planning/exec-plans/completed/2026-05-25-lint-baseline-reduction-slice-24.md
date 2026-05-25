# Lint Baseline Reduction Slice 24

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 24 完成历史，记录本轮继续在 `frontend/webApp` 做的一组异常语义尾项收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 30 条降到 25 条。
- `frontend/webApp` 的 `Main.kt` 已清空 `EmptyCatchBlock` 与 `UseCheckOrError` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 24: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中为返回流程、页面卸载清理、ASR 停止音轨、私聊跳转前断链补齐显式日志，并将文件列表加载里的 `throw IllegalStateException("HTTP ...")` 改成 `check(...)`，删除对应 3 条 baseline。
2. Slice 24: 在 `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt` 中抽出 `buildCreateWorkflowPayload(...)`，把 `createWorkflow()` 收敛到 cancellation-safe 恢复 helper，并把 `put()` 的泛 `Exception` 改成 `error(...)`，删除 `NestedBlockDepth` 与 `TooGenericExceptionThrown` 2 条 baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `ApiClient.kt$ApiClient$e: Exception` 对应的是整文件级的 catch 签名抑制，不是 `createWorkflow()` 独占；本轮一度尝试删除后，被根 `silkLint` 证明会一次性暴露整文件剩余泛 catch。因此当前只保留真实已修掉的 `NestedBlockDepth` / `TooGenericExceptionThrown` 删除，把那两条签名级 baseline 留到后续成组收敛。
- `frontend/webApp` 剩余尾项已经进一步收敛到 `CyclomaticComplexMethod` 18、`ComplexCondition` 4、`LoopWithTooManyJumpStatements` 1，以及 `ApiClient.kt` 的 2 条签名级异常规则。
