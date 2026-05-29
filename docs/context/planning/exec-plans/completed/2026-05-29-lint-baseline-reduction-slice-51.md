# Lint Baseline Reduction Slice 51

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 51 完成历史，记录本轮在 `frontend/androidApp` 的 `WorkflowChatScreen.kt` 上做的工作流连接异常收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 42 条降到 38 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/WorkflowChatScreen.kt` 清掉了 `TooGenericExceptionCaught` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 51: 把 `WorkflowChatScreen.kt` 首次连接工作流 WebSocket 的 `try/catch` 改成 `runCatching + cancellation 透传 + 非取消异常日志` 模式，保留“连接异常时打印日志、切 workflow 或页面销毁时安静取消”的原有语义，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 到这一轮为止，Android 侧剩余 `TooGenericExceptionCaught` 已经只落在 `AppState.kt` 与 `ChatScreen.kt`。
- `WorkflowChatScreen.kt` 其余主要剩余项已经是复杂度，而不是异常语义；后续如果回到这个文件，优先按 scene/toolbar/dialog helper 拆复杂度，不要再动连接恢复层。
