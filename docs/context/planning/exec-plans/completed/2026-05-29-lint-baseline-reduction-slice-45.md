# Lint Baseline Reduction Slice 45

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 45 完成历史，记录本轮在 `frontend/androidApp` 的 `AudioDuplexScreen.kt` 上做的异常恢复语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 53 条降到 50 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/AudioDuplexScreen.kt` 清掉了会话级 `TooGenericExceptionCaught` 和两条 `SwallowedException` baseline。
- 本轮没有改协议、WebSocket payload 或跨端消息合同。

## Completed Slice

1. Slice 45: 把 `runAudioDuplexSession(...)` 的 `catch (CancellationException)` / `catch (Exception)` 收敛成 `runCatching + cancellation passthrough + non-cancellation fallback` 模式，保留“用户停止/页面销毁时安静退出、连接异常时回写状态、finally 里统一释放音频资源”的原有语义；同时把 `AudioTrack` / `AudioRecord` 在关键分支上固定为局部非空引用，避免为 lint 收敛引入新的 Kotlin 编译噪音。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮没有额外跑 `:frontend:androidApp:testDebugUnitTest`；改动只落在 Android 音频双工本地会话错误恢复，按 active plan 先走 `detekt + compileDebugKotlin + silkLint` 的最窄验证链路。
- `AudioDuplexScreen.kt` 里剩余的 `LoopWithTooManyJumpStatements` 仍然保留在 baseline，适合作为下一轮单独拆 receive loop 的 slice，不和这次异常语义收敛混在一起。
