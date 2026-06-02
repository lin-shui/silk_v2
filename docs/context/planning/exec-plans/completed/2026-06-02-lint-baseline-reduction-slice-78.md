# Lint Baseline Reduction Slice 78

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 78 完成历史，记录本轮继续在 `frontend/androidApp` 的 `WorkflowDialogs.kt` 上处理 `FolderPickerDialog(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 12 条降到 11 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/WorkflowDialogs.kt` 清掉了 `FolderPickerDialog(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改目录列举协议、路径拼接语义、信任目录校验链路或工作流设置接口合同。

## Completed Slice

1. Slice 78: 把 `FolderPickerDialog(...)` 收敛成 header、breadcrumbs、列表区、loading/error 状态和 footer/action helper，保留原有 `requestLoad(...)` 编排与路径跳转语义。
2. Slice 78: 从 `frontend-androidApp.xml` 移除 `WorkflowDialogs.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `FolderPickerDialog(...)` 现在只保留目录加载状态和 dialog 编排；面包屑、列表区、手动跳转和确认动作都已经下沉到 helper。后续继续改 Android 工作流目录选择器时，优先沿这些 helper 扩展，不要把 loading/error/list/footer 分支重新塞回主 dialog。
