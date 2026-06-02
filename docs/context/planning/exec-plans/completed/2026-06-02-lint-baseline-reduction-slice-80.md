# Lint Baseline Reduction Slice 80

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 80 完成历史，记录本轮继续在 `frontend/androidApp` 的 `GroupListScreen.kt` 上处理 `GroupMembersListDialog(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 10 条降到 9 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/GroupListScreen.kt` 清掉了 `GroupMembersListDialog(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改群成员列表接口、联系人判定规则、群主标记规则或点击成员后的导航合同。

## Completed Slice

1. Slice 80: 把 `GroupMembersListDialog(...)` 收敛成标题、loading/empty/list 状态、成员行状态与头像/文案/CTA helper。
2. Slice 80: 从 `frontend-androidApp.xml` 移除 `GroupListScreen.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `GroupMembersListDialog(...)` 现在只保留 dialog 壳层和主状态分发；成员身份判定、头像、说明文案和右侧动作指示都已下沉到 helper。后续继续改成员弹窗时，优先沿这些 helper 扩展，不要把多重身份分支重新堆回主 dialog。
