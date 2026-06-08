# Lint Baseline Reduction Slice 116

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 116 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/AsrRoutes.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 82 条降到 81 条。
- `AsrRoutes` 不再使用 `catch (e: Exception)`；ASR 请求解析、上游连接和本地 ffmpeg 转码现按 `SerializationException` / `ConnectException` / `IOException` / `SecurityException` 分层回退。
- `transcodeToWavWithFfmpeg()` 的临时文件清理也不再依赖 broad catch；无论转码失败还是请求体非法，都继续保持既有的用户可理解错误响应。

## Completed Slice

1. Slice 116: 清理 `AsrRoutes.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 116: 保持坏 base64 音频请求返回 400、ASR 上游不可连通返回 503 的既有语义，不把输入校验失败误报成服务端 500。
3. Slice 116: 新增 `AsrRoutesTest`，锚定非法 base64 请求仍按合同返回 `BadRequest`。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.AsrRoutesTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`

## Notes

- 这轮选择 `AsrRoutes.kt`，是因为它虽然在 `Routing.kt` 外，但仍是清晰的单一路由边界，适合继续按“小文件、单规则、带 HTTP 合同测试”的节奏推进。
- 后续若继续沿异常语义推进，优先还是应按单一路由族拆 `Routing.kt`；如果转向更低风险的小文件，也可以继续清理剩余独立服务类里的 broad catch。
