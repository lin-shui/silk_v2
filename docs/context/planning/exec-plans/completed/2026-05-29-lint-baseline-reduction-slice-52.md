# Lint Baseline Reduction Slice 52

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 52 完成历史，记录本轮在 `frontend/androidApp` 的 `AppState.kt` 上做的本地会话恢复与验证异常收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 38 条降到 34 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/AppState.kt` 清掉了 2 条 `ComplexCondition`、1 条 `SwallowedException` 和 1 条 `TooGenericExceptionCaught` baseline。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ApiClient.kt` 的 `validateUser(...)` 现在会显式返回网络失败文案，和 `AppState.revalidateUser()` 的“网络错误不登出”语义保持一致。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 52: 把 `AppState.kt` 的本地用户恢复统一收敛到 `readStoredUser(...)` helper，去掉重复的四段非空并列条件。
2. Slice 52: 把应用版本信息读取收敛到 `loadPackageInfoOrNull()`，保留 `NameNotFoundException` 的日志，不再静默吞掉异常。
3. Slice 52: 把 `revalidateUser()` 改成基于 `ApiClient.validateUser(...)` 返回值区分“验证成功 / 网络失败 / 服务器拒绝”，透传 coroutine cancellation，保留离线时不登出的原有目标语义。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 到这一轮为止，Android 侧剩余 `TooGenericExceptionCaught` 与 `SwallowedException` 都只落在 `ChatScreen.kt`。
- `AppState.kt` 的本地会话恢复和验证状态现在都集中在 helper 与 `isNetworkFailure()` 语义上；后续改启动恢复/登出逻辑时，不要再回到重复空值并列判断或 `catch (Exception)` 模式。
