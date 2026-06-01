# Lint Baseline Reduction Slice 57

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 57 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的 inline markdown / inline math 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 21 条降到 19 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `InlineMarkdownAndroid(...)` 与 `extractInlineMath(...)` 两条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 19 条 `CyclomaticComplexMethod`，仍然集中在少数 Compose host / markdown renderer 大函数。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 57: 把 `InlineMarkdownAndroid(...)` 的 token 识别拆到 `findNextInlineMarkdownMatch(...)`、`appendInlineMarkdownMatch(...)` 等 helper，收敛 composable 内联分支。
2. Slice 57: 把 `extractInlineMath(...)` 的 `\\(...\\)` 与 `$...$` 扫描拆到 delimiter-specific helper，收敛主循环复杂度。
3. Slice 57: 同步删除 `frontend-androidApp.xml` 里对应的两条 `CyclomaticComplexMethod` baseline，并保持链接、粗体、斜体、行内代码与公式渲染语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮之后，Android 侧剩余复杂度已经更集中到 markdown block renderer、成员弹窗和顶层 host composable；后续继续切 `ChatScreen.kt` 时，优先沿现有 token/render helper 扩展，不要把判断链重新堆回 composable 本体。
