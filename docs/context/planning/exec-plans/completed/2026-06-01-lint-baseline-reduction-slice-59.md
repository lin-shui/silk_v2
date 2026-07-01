# Lint Baseline Reduction Slice 59

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 59 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的 markdown content renderer 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 18 条降到 17 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `MarkdownContentAndroid(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 17 条 `CyclomaticComplexMethod`，进一步集中到消息卡片、成员弹窗和顶层页面函数。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 59: 把 `MarkdownContentAndroid(...)` 改成 `parseMarkdownContent(...)` + `RenderMarkdownContentItem(...)` 两段式结构，主 composable 只保留 item 渲染循环。
2. Slice 59: 把 code block、math block、table block 和 heading/list/quote/paragraph 分类拆到 parser helper 和局部 composable helper，收敛原始 `when` 分支。
3. Slice 59: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline，并保持 markdown 备用 renderer 的表格、公式、列表、引用和分隔线渲染语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮之后，Android 端 markdown 备用 renderer 已经基本完成从“大 `when`”到“parser/render helper”的迁移；后续如果还在 `ChatScreen.kt` 收复杂度，优先转向 `FileItemCard(...)`、`ForwardedMessageBubble(...)` 这类局部消息卡片函数。
