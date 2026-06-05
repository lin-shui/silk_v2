# Lint Baseline Reduction Slice 91

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 91 完成历史，记录本轮继续在 `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 上做的一组会话发送与异常语义收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 144 条降到 141 条。
- `WebSocketConfig.kt` 清掉了 2 条 `SwallowedException` baseline 和 1 条陈旧的 `TooGenericExceptionCaught(ex)` baseline。
- 历史消息类型恢复不再依赖 `valueOf(...)` 的 broad catch，而是改成无异常的枚举解析 fallback。
- 会话广播、状态广播、停止生成回传、流式增量和撤回通知现在统一复用 `sendFrameSafely(...)`，发送失败会保留结构化异常日志。
- 本轮没有改消息 payload、WebSocket 协议、AI 触发条件或群聊/私聊判定规则。

## Completed Slice

1. Slice 91: 为 `WebSocketConfig.kt` 增加 `parseStoredMessageType(...)`，去掉历史消息恢复里的异常驱动 fallback。
2. Slice 91: 把多处空 catch / `printStackTrace()` / 只记 `message` 的发送分支收敛到 `sendFrameSafely(...)` 与结构化日志。
3. Slice 91: 让 `launchActiveAiJob(...)` 在捕获 `CancellationException` 后记录并继续透传，避免 cancellation 被吞掉。
4. Slice 91: 从 `backend.xml` 移除 `WebSocketConfig.kt` 对应的 2 条 `SwallowedException` baseline 和 1 条失效的 `TooGenericExceptionCaught(ex)` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `WebSocketConfig.kt` 里仍保留 `TooGenericExceptionCaught(e)` baseline；本轮没有把聊天主链中的数据库、下载器、Agent 和 Weaviate 异常进一步拆型，避免把小 slice 膨胀成入口面重构。
- 这次复验表明 `backend:test` 中现有 Weaviate 未配置 warning / stacktrace 日志仍是既有测试噪音，不是本轮新增失败。
