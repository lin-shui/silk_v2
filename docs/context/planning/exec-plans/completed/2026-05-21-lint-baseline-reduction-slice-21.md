# Lint Baseline Reduction Slice 21

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 21 完成历史，记录本轮在 `frontend/webApp` 的 `GroupListScene.kt` 上做的异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 34 条降到 33 条。
- `frontend/webApp` 的 `TooGenericExceptionCaught` 从 5 条降到 4 条。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 21: 复用 `ExceptionRecovery.kt` 的 cancellation-safe 恢复 helper，清理 `frontend/webApp/src/main/kotlin/com/silk/web/GroupListScene.kt` 中加载语言偏好、加载群组/未读数、创建群组、加入群组这 4 处显式泛 catch，并删除对应 1 条 `TooGenericExceptionCaught` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮同样保留了原有 `finally` 语义，确保群组列表和对话框里的 `isLoading` 状态在协程取消时也能复位。
- `frontend/webApp` 剩余异常语义问题已经主要集中到 `Main.kt` 与 `ApiClient.kt`；其中 `Main.kt` 仍适合继续切小，`ApiClient.kt` 依旧更适合按整组 helper 一起收敛。
