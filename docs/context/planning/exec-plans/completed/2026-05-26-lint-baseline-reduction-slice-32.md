# Lint Baseline Reduction Slice 32

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 32 完成历史，记录本轮继续在 `frontend/webApp` 的 `GroupListScene.kt` 上做的一组对话框级复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 15 条降到 12 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 清掉了 `CreateGroupDialog(...)`、`JoinGroupDialog(...)`、`GroupMembersListDialog(...)` 3 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 32: 在 `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 中抽出 create/join dialog 的公共壳层、输入区、错误提示和操作按钮 helper，并把群成员弹窗拆成内容区、成员 display state 和成员行 helper，删除对应 3 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 首次跑 `:frontend:webApp:detekt` 时被一个存活 17 天的 idle Gradle daemon 占住 `modules-2.lock`；本轮先用 `./gradlew --status` 确认为 idle，再执行 `./gradlew --stop` 释放锁后继续验证。
- 群成员弹窗一开始把所有角色判断集中在 `buildGroupMemberDisplayState(...)`，detekt 报了新的复杂度超阈值；最终把 avatar/status/action 判定再拆成更小的纯 helper 后完成收口。
