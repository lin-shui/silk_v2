# Lint Baseline Reduction Slice 170

这份归档保留 `lint-baseline-reduction` 的 Slice 170 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Routing.kt` 上清理 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Result

- `config/lint/detekt/backend.xml` 从 10 条降到 9 条。
- 删除 `Routing.kt$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- `Routing.kt` 的用户设置、Bridge 文件系统、trusted-dir、认证/群组/联系人、todo、消息、Agent Bridge、聊天 WebSocket 与 Audio Duplex 代理不再保留宽 `catch (e: Exception)`；统一改为 `runCatching` 并保留 cancellation 语义。
- 顺手移除 route 编译期噪音：`rethrowRoutingCancellation()` 不再无意义 inline，删除群组时不再绑定未使用的 `removedMembers`。

## Behavior Notes

- HTTP 路由的原状态码与响应体保持不变：请求解析失败仍按原 400/500 分支返回。
- best-effort 广播、workflow 初始 `/cd`、Agent Bridge 初始化、聊天 WebSocket 和 Audio Duplex 代理继续按原语义记录失败并执行连接/会话清理。
- 尝试删除 `configureRouting()` 的复杂度 baseline 时，`:backend:detekt` 仍报告 `Routing.kt` complexity 293/15；该结构性拆分留给后续 slice。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:detekt :backend:test silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Follow-up

- 下一轮优先处理 `Routing.kt` 的 `configureRouting()` 复杂度 baseline，按路由族拆入口，不和 `LargeClass` 混在同一轮。
- `LargeClass` 仍剩 8 条，属于后续结构性收敛。
