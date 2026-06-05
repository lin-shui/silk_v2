# Lint Baseline Reduction Slice 93

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 93 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/utils/WebPageDownloader.kt` 上继续收敛下载器异常恢复语义。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 139 条降到 138 条。
- `WebPageDownloader.kt` 不再使用 broad `catch (Exception)` 包裹 Playwright、HTTP、PDF 和 URL parse 回退路径。
- 资源关闭与失败日志改成 `runCatching(...)` + 明确 `onFailure`，同时保留“失败时回退/返回 null、不向上抛出下载器内部异常”的既有语义。
- 本轮没有改变 URL 提取规则、Playwright 优先级、HTTP/PDF 分流条件或 `WebPageContent` 返回契约。

## Completed Slice

1. Slice 93: 把 `WebPageDownloader.kt` 中初始化、关闭、URL 判定、Playwright 下载、HTTP 下载、PDF 下载和标题提取等路径上的 broad catch 收敛成 `runCatching(...)`。
2. Slice 93: 为 Playwright page/context 关闭补充 `closePlaywrightResource(...)`，避免清理动作把主流程错误重新放大。
3. Slice 93: 从 `backend.xml` 移除 `WebPageDownloader.kt` 对应的 `TooGenericExceptionCaught` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮只收异常语义，不碰 `WebPageDownloader.kt` 里剩余的结构复杂度与下载策略。
- 下一步如果继续切 backend，仍应优先挑 `Routing.kt` 或其他单文件中的同类异常点，不要把复杂度和恢复语义混成一轮。
