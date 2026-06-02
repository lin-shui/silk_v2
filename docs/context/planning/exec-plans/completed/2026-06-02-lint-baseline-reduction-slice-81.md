# Lint Baseline Reduction Slice 81

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 81 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ContactsScreen.kt` 上处理 `ContactsScreen(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 9 条降到 8 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ContactsScreen.kt` 清掉了 `ContactsScreen(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改联系人 API、联系人请求处理语义、私聊启动合同或页面导航目标。

## Completed Slice

1. Slice 81: 把 `ContactsScreen(...)` 顶层 scene 收敛成 top bar、body、列表内容和 dialog orchestration，并把加载语言、加载联系人与私聊启动抽成 helper。
2. Slice 81: 从 `frontend-androidApp.xml` 移除 `ContactsScreen.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt`
- `./gradlew :frontend:androidApp:compileDebugKotlin`
- `git diff --check`

## Notes

- `ContactsScreen(...)` 现在只保留页面状态、effect 绑定和主要回调编排；顶部栏、loading/content 切换、联系人列表和 dialog 挂载都已下沉到 helper。后续继续改联系人页时，优先沿这些 helper 扩展，不要把列表分支、私聊启动和对话框逻辑重新堆回顶层 composable。
