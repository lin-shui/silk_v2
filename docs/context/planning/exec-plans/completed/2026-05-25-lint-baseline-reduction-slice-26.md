# Lint Baseline Reduction Slice 26

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 26 完成历史，记录本轮在 `frontend/webApp` 的 `WorkflowScene.kt` 与 `Main.kt` 上做的一组复杂条件收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 24 条降到 20 条。
- `frontend/webApp` 已清空 `ComplexCondition` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 26: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中新增 `shouldRenderInlineTransientMessage(...)` 与 `isWorkflowAgentLifecycleMessage(...)`，并在 `WorkflowScene.kt` 中补 `shouldSubmitWorkflowMessage(...)`，把 transient 消息展示、workflow agent 生命周期判断和 Enter 发送条件改成语义化 helper，删除对应 4 条 `ComplexCondition` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这次把 `Main.kt` 与 `WorkflowScene.kt` 中重复出现的 transient / agent-status 判断合并成共享 helper，后续新增消息渲染分支时应优先复用，而不是重新拷贝复杂条件。
- `frontend/webApp` 在这一刀之后只剩复杂度大函数和 `ApiClient.kt` 的 2 条签名级异常规则，更适合继续按单文件 UI 场景慢拆。

