# Lint Baseline Reduction Slice 54

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 54 完成历史，记录本轮在 `frontend/androidApp` 的 `ApkDownloader.kt` 与 `MarkdownWebView.kt` 上做的 locale 显式化收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 31 条降到 29 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ApkDownloader.kt` 与 `MarkdownWebView.kt` 清掉了 2 条 `ImplicitDefaultLocale` baseline。
- Android 侧剩余 baseline 现在只包含复杂度/条件/嵌套类问题。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 54: 为 APK 文件大小格式化显式指定 `Locale.US`，避免依赖系统默认 locale。
2. Slice 54: 为 Markdown WebView 的 Unicode 转义十六进制格式化显式指定 `Locale.US`，避免不同地区设置下的格式化漂移。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一刀是纯静态分析修复，不改 UI 文案或业务流程。
- 到这一轮为止，Android 侧已无异常类或 locale 类 detekt baseline，剩余项都在复杂度面。
