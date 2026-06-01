# Lint Baseline Reduction Slice 58

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 58 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的 markdown table 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 19 条降到 18 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `MarkdownTableAndroid(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 18 条 `CyclomaticComplexMethod`，进一步集中到 markdown host、大型卡片和顶层页面函数。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 58: 把 `MarkdownTableAndroid(...)` 的 header 检测、行解析和列数计算收敛到 `ParsedMarkdownTable` 与 parser helper。
2. Slice 58: 把表头、数据行、通用单元格和尾部空列补齐拆成独立 composable helper，避免把渲染分支堆回主函数。
3. Slice 58: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline，并保持表格 header、交替底色、行内 markdown 单元格渲染语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮之后，Android markdown renderer 剩余的主要复杂度焦点已经进一步收缩到 `MarkdownContentAndroid(...)`；下一轮如果继续留在 `ChatScreen.kt`，优先复用本轮落下来的 parser/render helper，不要重新把表格判定和单元格填充写回主 renderer。
