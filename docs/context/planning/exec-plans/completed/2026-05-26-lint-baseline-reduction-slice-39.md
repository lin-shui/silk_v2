# Lint Baseline Reduction Slice 39

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 39 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的成员弹窗复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 6 条降到 5 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 清掉了 `MembersDialog(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 39: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中把 `MembersDialog(...)` 拆成 overlay、surface、状态体、成员列表和单成员行 helper，并把成员身份/交互状态收口到 `MembersDialogMemberState`，保留原有“自己不可点击、AI 不可点击、联系人点进私聊、非联系人点加好友”的语义，同时删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 本轮一度引入了一个 helper 未使用参数，detekt 当场报出 `UnusedParameter`；已在同轮删除该参数后复跑通过，没有留下新的 lint 噪音。
- 这一步继续停留在弹窗/host 层，没有摊开 `ChatAppWithGroup(...)`、`MessageItem(...)` 或 `AIMessageCard(...)` 的消息渲染逻辑。
