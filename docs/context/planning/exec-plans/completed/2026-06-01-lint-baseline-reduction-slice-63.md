# Lint Baseline Reduction Slice 63

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 63 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的 AI 消息卡片复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 14 条降到 13 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `AIMessageCardAndroid(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 13 条 `CyclomaticComplexMethod`，继续集中在 `ChatScreen.kt` 的消息分发/页面 host 以及其他少数大 composable。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 63: 把 `AIMessageCardAndroid(...)` 拆成 `AIMessageCardState`、header、thinking panel、body、footer 等 helper，让主 card composable 只保留状态装配和块级编排。
2. Slice 63: 把 AI 名称格式化、thinking/body 拆分、长文 preview 生成收敛到纯函数 helper，避免把 `<!--THINKING_END-->` 与长文展开条件散回主 composable。
3. Slice 63: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline，并保持 AI 长文展开/收起、思考过程折叠、复制转发和 transient “生成中” 状态语义不变。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮再次验证了 Compose scope helper 的边界：凡是内部要用 `weight(...)` 的 UI helper，优先保留成 `RowScope`/`ColumnScope` extension，而不是机械提成普通 composable。
