# Lint Baseline Reduction Slice 43

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 43 完成历史，记录本轮在 `frontend/webApp` 的 `ApiClient.kt` 上做的异常恢复语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 2 条降到 0 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt` 清掉了整文件遗留的 `TooGenericExceptionCaught` / `SwallowedException` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 43: 在 `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt` 内新增 `recoverApiCall(...)` helper，把注册、登录、群组、联系人、工作流、TrustedDir、知识库、ASR 等 API 方法从 `catch (Exception)` 统一收敛到 non-cancellation recovery 模式，保留原有日志和 fallback 返回语义，同时删除对应的 web detekt baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮先试过“直接删 baseline”验证是否为陈旧项，结果 `detekt` 立即重新暴露出 `ApiClient.kt` 的整文件泛 catch；因此最终选择单文件 helper 化，而不是继续保留签名级 baseline 黑洞。
- `recoverApiCall(...)` 复用现有 `recoverSuspendNonCancellation(...)`，因此保留了 coroutine cancellation 原样透传的行为；后续 web API 新增方法应优先沿这个 helper 扩展。
