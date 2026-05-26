# Lint Baseline Reduction Slice 37

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 37 完成历史，记录本轮继续在 `frontend/webApp` 的 `GroupListScene.kt` 上做的群组页顶层 scene 编排复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 8 条降到 7 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 清掉了 `GroupListScene(appState: WebAppState)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 37: 在 `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 中把顶层 scene 拆成 effect、header、content、overlay 与小型 suspend helper，保留原有群组加载、未读轮询、删除/退出模式、成员弹窗和 Silk 私聊入口语义，同时删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮把 `GroupListScene(...)` 的顶层编排从“单函数同时承担 effect、按钮分支、列表渲染和弹窗调度”收成了几块独立 host/helper，后续继续改群组页时优先在这些 helper 上扩展，不要把分支重新堆回顶层 scene。
- `:frontend:webApp:nodeTest` 首次通过时提示我刚引入了几处多余的 `!!`；已在同轮顺手消除，再复跑 `nodeTest + silkLint` 确认没有留下新的编译或 lint 噪音。
