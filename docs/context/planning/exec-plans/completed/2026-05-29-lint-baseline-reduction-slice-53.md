# Lint Baseline Reduction Slice 53

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 53 完成历史，记录本轮在 `frontend/androidApp` 的 `ChatScreen.kt` 上做的异常恢复语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 34 条降到 31 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ChatScreen.kt` 清掉了旧的 `EmptyCatchBlock` / `TooGenericExceptionCaught` / `SwallowedException` baseline，并顺手修掉了同文件里未入 baseline 的同类 detekt 命中点。
- Android 侧已无异常类 detekt baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 53: 把 `ChatScreen.kt` 里依赖 `ApiClient` 恢复语义的调用改成直接检查返回值，不再额外包 `catch (Exception)`。
2. Slice 53: 把聊天连接、文件上传、ASR、外链打开和文件列表读取等“失败就记录/回退”的操作统一收敛到 `runCatching` 或 `runLoggedSuspendAction(...)` helper，保留 cancellation 透传。
3. Slice 53: 把链接打开失败和文件列表/上传失败改成显式日志或 fallback，不再静默吞异常。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一轮 detekt 暴露出此前 active plan 对 `ChatScreen.kt` 异常余量的摘要已经过期；清掉旧 baseline 后，源码里实际还有一组未入 baseline 的 generic catch / swallowed exception。现在这些点已经一起收敛完成。
- `ChatScreen.kt` 后续如果继续改连接、上传、ASR 或外链行为，优先沿用 `runLoggedSuspendAction(...)` 与 `disconnectChatClientQuietly(...)`，不要再回到整段 `catch (Exception)`。
