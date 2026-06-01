# Lint Baseline Reduction Slice 62

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 62 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的成员弹窗复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 15 条降到 14 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `MembersDialog(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 14 条 `CyclomaticComplexMethod`，进一步集中到 AI 消息卡片、消息项分发和顶层页面函数。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 62: 把 `MembersDialog(...)` 拆成 header、body、loading/empty/list、member row 四层 helper，让主 dialog 只保留壳层和关闭动作。
2. Slice 62: 把成员身份判定与展示文案/头像/操作提示收敛到 `MembersDialogMemberState` 和字段级 helper，避免把 `isCurrentUser`、`isContact`、`isSilkAI` 的分支散在 row composable 里。
3. Slice 62: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline，并保持成员点击、联系人聊天入口、添加联系人提示和当前用户/AI 标识语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮的主要教训是 `ColumnScope.weight(...)` 一旦被提到普通 helper 就会丢失作用域；后续拆 Compose host 时，优先保留需要 `weight`/`align` 的 helper 作为 scope extension，而不是硬搬成普通 composable。
