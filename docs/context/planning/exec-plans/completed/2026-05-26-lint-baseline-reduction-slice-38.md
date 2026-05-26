# Lint Baseline Reduction Slice 38

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 38 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的聊天页顶层 scene 编排复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 7 条降到 6 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 清掉了 `ChatScene(appState: WebAppState)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 38: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中把 `ChatScene(...)` 拆成 missing-context、effects、sidebar header/content/card 与 unread badge 等 helper，并把 sidebar 刷新/未读轮询提成小型 suspend helper，保留原有群组切换、标记已读和进入 `ChatAppWithGroup(...)` 的语义，同时删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:detekt :frontend:webApp:nodeTest silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮只处理 `ChatScene(...)` 的 scene/host 编排，没有摊开 `ChatAppWithGroup(...)`、`MessageItem(...)` 或 `AIMessageCard(...)` 这些更深的会话/消息渲染逻辑。
- `nodeTest + silkLint` 过程中暴露的是 `Main.kt` 里既有 Kotlin 编译 warning（和本轮无关的未使用变量/多余 `!!`），本轮没有新增 detekt 规则问题。
