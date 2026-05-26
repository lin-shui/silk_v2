# Lint Baseline Reduction Slice 36

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 36 完成历史，记录本轮继续在 `frontend/webApp` 的 `WorkflowScene.kt` 上做的一组顶层 scene 编排复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 9 条降到 8 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 清掉了 `WorkflowScene(appState: WebAppState)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 36: 在 `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 中抽出左侧列表、右侧主面板、创建工作流流程、目录信任确认、动作菜单、重命名和删除确认等 scene 级 host/helper，并把创建 workflow 的默认值初始化、信任检查和创建提交动作外提到小型函数，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮一开始 `:frontend:webApp:nodeTest` 因 `WorkflowSidebarMessage(...)` 中的 `padding(...)` 写法笔误报 Kotlin 编译错误；修正为显式 `if/else` 后恢复通过，没有新增 lint 规则问题。
- `WorkflowCreateFlow(...)` 继续保留“打开创建弹窗时只初始化一次默认 agent / 默认目录”和“未信任目录先弹 Silk 风格信任确认”的原有语义，本轮只做 scene 分层，没有改 `listAgents(...)`、`listCcDir(...)`、`checkTrustedDir(...)` 或 `createWorkflow(...)` 的业务顺序。
