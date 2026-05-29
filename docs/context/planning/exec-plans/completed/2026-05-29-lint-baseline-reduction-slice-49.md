# Lint Baseline Reduction Slice 49

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 49 完成历史，记录本轮在 `frontend/androidApp` 的登录/升级安装链路上做的一组异常恢复语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 47 条降到 43 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ApkDownloader.kt` 清掉了 `PrintStackTrace`、`SwallowedException`、`TooGenericExceptionCaught` 和 `TooGenericExceptionThrown` baseline。
- `frontend/androidApp/src/main/kotlin/com/silk/android/LoginScreen.kt` 与 `VersionChecker.kt` 清掉了对应的 `TooGenericExceptionCaught` / `PrintStackTrace` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 49: 把 `ApkDownloader.downloadApk(...)` / `installApk(...)` 改成 `runCatching + cancellation 透传 + 状态/返回值汇报`，不再 `printStackTrace()` 或向调用方抛 `RuntimeException`；同时把 `LoginScreen.kt` 的登录注册流程改为直接依赖 `ApiClient.login/register(...)` 的 fallback 响应，把 `VersionChecker.kt` 的版本检查与安装流程改为直接消费 `ApiClient.getAppVersion()` / `ApkDownloader.installApk(...)` 的结果，删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 登录页和版本检查器都继续保留原有的用户可见反馈，只是错误来源从页面侧 `catch` 改成统一读取 `response.message` 或安装 helper 返回值。
- 这一轮把 Android 侧的 `PrintStackTrace` 和 `TooGenericExceptionThrown` baseline 一次性清空了，后续升级入口继续复用 `ApkDownloader.installApk(...)` 即可。
