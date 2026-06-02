# Lint Baseline Reduction Slice 83

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 83 完成历史，记录本轮继续在 `frontend/androidApp` 的 `WorkflowScreen.kt` 上处理 `CreateWorkflowDialog(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 7 条降到 6 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/WorkflowScreen.kt` 清掉了 `CreateWorkflowDialog(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改工作流创建 API、agent 类型字段、权限模式取值或 trusted-dir 合同。

## Completed Slice

1. Slice 83: 把 `CreateWorkflowDialog(...)` 收敛成初始化加载、表单分区、目录提示与辅助对话框/信任创建 helper，并把提交流程从主 dialog 壳层拆开。
2. Slice 83: 从 `frontend-androidApp.xml` 移除 `WorkflowScreen.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt`
- `./gradlew :frontend:androidApp:compileDebugKotlin`
- `git diff --check`

## Notes

- `CreateWorkflowDialog(...)` 现在只保留状态装配和主弹窗壳层；agent/权限/目录输入、默认值初始化、信任检查与后续创建动作都已下沉到 helper。后续继续改创建工作流弹窗时，优先沿这些 helper 扩展，不要把表单分支和 trust-check 流程重新堆回主 dialog。
