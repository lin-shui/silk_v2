# Lint Baseline Reduction Slice 77

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 77 完成历史，记录本轮回到 `frontend/androidApp` 的 `ChatScreen.kt`，处理 `MessageItem(...)` 的 render-mode dispatch 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 13 条降到 12 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `MessageItem(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改消息 payload、文件下载 URL 拼接规则、转发内容解析协议或 AI / 系统消息显示合同。

## Completed Slice

1. Slice 77: 把 `MessageItem(...)` 收敛成 AI/file/system/regular 四类 render-mode dispatch，并把文件卡片、系统提示、消息菜单、PDF 下载体、视觉状态和选择态容器拆成 helper。
2. Slice 77: 从 `frontend-androidApp.xml` 移除 `ChatScreen.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `MessageItem(...)` 现在只保留消息类型分发；文件消息打开、PDF 下载按钮、消息操作菜单和选择态手势都已经下沉到 helper。后续继续切 Android 消息列表时，优先沿这些 helper 扩展，不要把文件/PDF/menu 分支重新堆回主 composable。
