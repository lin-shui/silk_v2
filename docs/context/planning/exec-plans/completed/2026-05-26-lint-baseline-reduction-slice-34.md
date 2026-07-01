# Lint Baseline Reduction Slice 34

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 34 完成历史，记录本轮继续在 `frontend/webApp` 的 `WorkflowScene.kt` 上做的一组 Folder Picker 复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 11 条降到 10 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 清掉了 `FolderPickerDialog(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 34: 在 `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt` 中抽出 `FolderPickerDialog(...)` 的 header、breadcrumbs、目录列表区、状态消息和 footer helper，保留 `requestLoad(...)` 的取消旧请求语义不变，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 目录加载逻辑仍然通过 `loadJob?.cancel()` 保证只有最新请求可以写回 state，本轮只做 UI 分层，没有改 `listCcDir` / `joinPath` / `buildBreadcrumbPath` 的行为。
- `FolderPickerDialog(...)` 已按 `WEB.md` 的现有约束继续复用 `ModalOverlay`，也仍然依赖后端返回的 `separator` 做路径拼接，没有把 Unix/Windows 规则重新内联到 UI。
