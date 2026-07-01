# Lint Baseline Reduction Slice 44

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 44 完成历史，记录本轮在 `frontend/androidApp` 的 `ApiClient.kt` 上做的异常恢复语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 57 条降到 53 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ApiClient.kt` 清掉了 `TooGenericExceptionCaught`、`SwallowedException` 和 `InstanceOfCheckForException` 这一组遗留 baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 44: 在 `frontend/androidApp/src/main/kotlin/com/silk/android/ApiClient.kt` 内新增 `recoverApiCall(...)` helper，把注册、登录、群组、联系人、工作流、TrustedDir、知识库、ASR 等 API 方法从分散的 `catch (Exception)` / `if (e is CancellationException)` 收敛到统一的 `Dispatchers.IO + non-cancellation recovery` 模式，保留原有日志和 fallback 返回语义，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:testDebugUnitTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮最初把 `compileDebugKotlin` 和 `testDebugUnitTest` 并行跑时，`frontend:shared:compileDebugKotlinAndroid` 出现了增量产物读取失败；串行重跑 `compileDebugKotlin` 后恢复通过，因此该异常可视为并发构建噪音，不是这轮源码回归。
- `testDebugUnitTest` 串行重跑后仍被已知 `:frontend:androidApp:compileDebugJavaWithJavac -> androidJdkImage -> JdkImageTransform/jlink` 阻塞；这是 active plan 里已记录的 Android 环境阻塞，不是本轮 `ApiClient.kt` 引入的新问题。
- `transcribeAudio(...)` 仍保留对 `ConnectException` 的用户友好兜底，但本轮补了日志，避免继续以 swallowed exception 形式留在 baseline。
