# Lint Baseline Reduction Slice 60

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 60 完成历史，记录本轮继续在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的文件卡片复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 17 条降到 16 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了 `FileItemCard(...)` 这一条 `CyclomaticComplexMethod` baseline。
- Android 侧 detekt baseline 现在只剩 16 条 `CyclomaticComplexMethod`，继续向更少的消息卡片和 host composable 聚拢。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 60: 把 `FileItemCard(...)` 的文件扩展名分流改成 `fileCardIconMappings` + `toFileCardIcon()` 数据驱动映射，去掉 composable 内联类型分支。
2. Slice 60: 保持文件卡片的点击、布局、大小格式化和上传时间展示语义不变。
3. Slice 60: 同步删除 `frontend-androidApp.xml` 里对应的 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮中途试过把分支简单平移到 helper，但 detekt 仍会拦截 helper 复杂度；最终稳定做法是把类型判断改成数据映射。后续同类收敛优先考虑“数据驱动替代条件链”，不要只做机械搬运。
