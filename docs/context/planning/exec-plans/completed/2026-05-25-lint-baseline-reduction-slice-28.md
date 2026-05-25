# Lint Baseline Reduction Slice 28

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 28 完成历史，记录本轮继续在 `frontend/webApp` 的 `ContactsScene.kt` 上做的一组低风险复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 19 条降到 18 条。
- `frontend/webApp` 的 `ContactsScene.kt` 已清空 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 28: 在 `frontend/webApp/src/main/kotlin/com/silk/web/ContactsScene.kt` 中抽出 `ContactsTopBar(...)`、`ContactsLoadingState(...)`、`PendingRequestsSection(...)`、`ContactsSection(...)`、`ContactsContent(...)` 等 UI section helper，并把 `currentUser` 提前收敛为前置条件，去掉主场景里的可空分支和重复渲染块，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮一开始仅靠 section 拆分还差 1 个复杂度点；最后通过把 `currentUser` 前置为必需依赖，去掉 `?.let` / `response != null` 分支，才让 `ContactsScene(...)` 低于 detekt 阈值。
- 联系人页的主要结构现在已经稳定成“顶部栏 + 内容区 + 弹窗”，后续如果继续加功能，优先向这些独立 composable 分发逻辑。
