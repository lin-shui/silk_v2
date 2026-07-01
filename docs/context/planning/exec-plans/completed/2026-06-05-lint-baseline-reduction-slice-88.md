# Lint Baseline Reduction Slice 88

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 88 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/AsrRoutes.kt` 上完成一条低风险异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 148 条降到 147 条。
- `AsrRoutes.kt` 清掉了 1 条 `SwallowedException` baseline。
- Base64 音频 payload 非法时，路由现在会记录明确的拒绝日志，再继续返回原有的 `400 Invalid base64 audio` 响应。
- 本轮没有改 ASR HTTP 合同、转码策略、上游 URL 拼装或成功/失败返回体结构。

## Completed Slice

1. Slice 88: 在 `AsrRoutes.kt` 的 base64 解码失败分支补充日志，保留原有 `400 BadRequest` 响应语义。
2. Slice 88: 从 `backend.xml` 移除 `SwallowedException:AsrRoutes.kt$e: IllegalArgumentException` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `AsrRoutes.kt` 的 `TooGenericExceptionCaught` baseline 仍保留；本轮不把整个路由错误分层一起重构，避免把一个小 slice 膨胀成多类异常治理。
- 后续若继续切 ASR 相关 lint，优先考虑把外层 broad catch 按请求解析、网络失败、上游返回异常分层，而不是机械替换成另一种泛型异常。
