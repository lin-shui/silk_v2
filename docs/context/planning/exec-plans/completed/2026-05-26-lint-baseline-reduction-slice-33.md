# Lint Baseline Reduction Slice 33

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 33 完成历史，记录本轮继续在 `frontend/webApp` 的 `GroupListScene.kt` 上做的一组群组卡片复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 12 条降到 11 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 清掉了 `GroupCard(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 33: 在 `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 中抽出 `GroupCard(...)` 的 visual state、未读 badge、右侧操作区、成员按钮和删除指示器 helper，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮 nodeTest 继续只报既有的 `GroupListScene.kt` 非空断言编译 warning，没有新增 lint 或测试失败。
- `GroupCard(...)` 的样式分支较多，但都属于显示态差异；因此优先抽纯 display helper，没有触碰列表选择、未读数或成员按钮交互语义。
