# Lint Baseline Reduction Slice 84

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 84 完成历史，记录本轮继续在 `frontend/androidApp` 的 `GroupListScreen.kt` 上处理 `GroupListScreen(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 6 条降到 5 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/GroupListScreen.kt` 清掉了 `GroupListScreen(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改群组/未读/联系人/私聊的后端合同，只收敛了 Android 宿主页的 UI 编排和本地辅助流程。

## Completed Slice

1. Slice 84: 把 `GroupListScreen(...)` 收敛成语言/列表刷新 effect、top bar、列表内容和 dialog host 四层 helper，并把退群、Silk 私聊、未读刷新和成员弹窗数据加载拆成独立 suspend helper。
2. Slice 84: 修正批量退群完成后 toast 数量会被提前清空成 0 的本地状态问题。
3. Slice 84: 从 `frontend-androidApp.xml` 移除 `GroupListScreen.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `GroupListScreen(...)` 现在只保留状态装配和 `Scaffold` 壳层；删除模式、顶部动作、列表分发和对话框编排都已下沉到 helper。后续继续改群组页宿主时，优先沿这些 helper 扩展，不要把顶部动作、未读处理和成员加载逻辑重新堆回主 composable。
