# Lint Baseline Reduction Slice 99

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 99 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Routing.kt` 上继续收敛 detekt 的 `SwallowedException` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 109 条降到 108 条。
- `Routing.kt` 的 Agent Bridge / group chat / audio duplex websocket 路径补齐了取消态与失败态日志，不再吞掉 `CancellationException`。
- `Routing.kt` 的消息解析 catch 进一步收紧为 `SerializationException` / `IllegalArgumentException`，避免把正常坏 payload 继续记成泛型解析异常。

## Completed Slice

1. Slice 99: 清理 `Routing.kt` 的 1 条 `SwallowedException: ... CancellationException` baseline。
2. Slice 99: 保持 websocket 行为不变，只把原先“静默忽略取消/关闭”的路径改成显式 debug / warn 记录。
3. Slice 99: 验证了 `Routing.kt` 里的 `TooGenericExceptionCaught: ... Exception` 与 `SwallowedException: ... Exception` 仍是文件级聚合签名，本轮不误删这两条 baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮证明 `Routing.kt` 的 broad `Exception` catch 还不能按“删一条 baseline”方式处理；要继续推进，必须先拆成更小的路由族或把接收/响应错误分类到更具体异常。
- 后续如果继续啃 `Routing.kt`，优先挑单一路由族里可明确分层的 parse/validation 失败，不要直接碰整文件聚合的 `Exception` baseline。
