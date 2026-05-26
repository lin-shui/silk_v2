# Lint Baseline Reduction Slice 35

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 35 完成历史，记录本轮继续在 `frontend/webApp` 的 `WorkflowScene.kt` 上做的一组工作流聊天面板复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 10 条降到 9 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 清掉了 `WorkflowChatPanel(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 35: 在 `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 中抽出 `WorkflowChatPanel(...)` 的 header、消息区、badge/dropdown、输入区和目录/信任弹窗入口 helper，并把发送消息、切换 agent、切换权限模式、目录确认等动作外提到小型函数，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮先让 `WorkflowChatPanel(...)` 只保留连接、状态同步和 effect 编排，agent/permission dropdown 与目录切换的分支都落到独立 helper，避免在主 composable 里继续堆交互分支。
- `WorkflowPermissionModeBadge(...)` 仍然只在已有 permission mode 时显示，`WorkflowAgentBadge(...)` 仍然沿用“已连接才能切换”的保护；本轮没有改 `ApiClient.updateCcSettings(...)`、`checkTrustedDir(...)` 或 `cdCcDir(...)` 的调用语义。
