# Lint Baseline Reduction Slice 46

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 46 完成历史，记录本轮继续在 `frontend/androidApp` 的 `AudioDuplexScreen.kt` 上做的 receive loop 控制流收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 50 条降到 49 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/AudioDuplexScreen.kt` 清掉了 `LoopWithTooManyJumpStatements` baseline；该文件现在只剩更细粒度的播放/解析辅助逻辑，不再靠 `continue` / `break` 驱动主 receive loop。
- 本轮没有改协议、WebSocket payload 或跨端消息合同。

## Completed Slice

1. Slice 46: 把 `processDuplexFrames(...)` 的 `for (frame in incoming)` 主循环改成调用 `processIncomingDuplexFrame(...)` helper，由 helper 返回是否停止会话，取代原先的 `continue` / `break` 跳转；保留文本帧解析、`prepared/result/audio_only/error/stopped` 事件语义，以及 `captureJob` 取消时机不变，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮没有额外跑 `:frontend:androidApp:testDebugUnitTest`；改动仍限定在 Android 音频双工 receive loop 的本地控制流，继续按 active plan 走最窄 lint 验证链路。
- `frontend/androidApp` 里剩余的 `LoopWithTooManyJumpStatements` 只剩 `ChatScreen.kt$while`，后续若继续清 Android 复杂度，优先单独切那条 while loop，避免把异常语义和 host 层 UI 编排混在一起。
