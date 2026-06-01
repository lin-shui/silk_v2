# Lint Baseline Reduction Slice 61

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 61 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的转发气泡复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 16 条降到 15 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `ForwardedMessageBubble(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 15 条 `CyclomaticComplexMethod`，进一步集中到 AI 消息卡片、成员弹窗和顶层页面函数。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 61: 把 `ForwardedMessageBubble(...)` 的批量解析、预览文本、气泡配色/圆角计算收敛到 `ForwardedMessageBubbleState` 和纯 helper，主 composable 只保留布局编排。
2. Slice 61: 把转发头部、发送者、折叠预览、展开内容、批量条目和展开/收起动作拆成独立 composable helper，避免把批量/单条和展开/收起分支堆回主函数。
3. Slice 61: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline，并保持转发来源、发送者展示、预览文案和批量分段渲染语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮的关键不是继续堆局部 `if` helper，而是先把转发气泡的“状态计算”和“渲染分段”分开；后续如果继续收同类消息卡片复杂度，优先沿 `state + section helper` 方式切，不要只做机械搬运。
