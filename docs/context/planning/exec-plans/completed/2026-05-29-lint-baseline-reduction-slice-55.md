# Lint Baseline Reduction Slice 55

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 55 完成历史，记录本轮在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的局部复杂条件与嵌套深度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 29 条降到 23 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 4 条 `ComplexCondition` 与 1 条 `NestedBlockDepth` baseline，另一个 `NestedBlockDepth` 也随同 helper 抽平一并消失。
- Android 侧已无 `ComplexCondition` 与 `NestedBlockDepth` detekt baseline，剩余项只在 `CyclomaticComplexMethod` 与 `LoopWithTooManyJumpStatements`。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 55: 把聊天空态展示条件收敛到 `shouldShowEmptyChatState(...)`，去掉主 `LazyColumn` 里的长布尔链。
2. Slice 55: 把 `highlightLine(...)` 的注释、字符串、数字、标识符、操作符与标点扫描条件拆成小 helper，保持现有代码高亮语义不变。
3. Slice 55: 把 `getFileName(...)` 的显示名查询收敛到 `queryDisplayName(...)`，去掉深层 cursor 嵌套。
4. Slice 55: 把 `extractInlineMath(...)` 的 `$...$` 起始判定收敛到 `startsInlineDollarMathAt(...)` helper，避免内联复杂条件。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一步结束后，Android detekt baseline 已只剩复杂度类问题，后续如果继续切 `ChatScreen.kt`，应优先从 token scan 或单 helper 层继续拆，不要直接动顶层聊天页面编排。
