# Slice 172

这份归档保留 `lint-baseline-reduction` 的 Slice 172 完成历史，记录本轮在 backend WebSocket 合同测试与知识库引用 helper 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `BackendWebSocketContractTest.kt$BackendWebSocketContractTest` 对应的 1 条 `LargeClass` baseline。
- `BackendWebSocketContractTest.kt` 只保留聊天回放与入群鉴权合同；URL 下载/失败链路拆到新的 `BackendWebSocketUrlIngestionContractTest.kt`，共享收发与断言 helper 下沉到 `BackendWebSocketTestSupport.kt`。
- 新增的知识库引用 helper 文件改名为 `KnowledgeBasePromptContext.kt`，清掉 detekt 的 `MatchingDeclarationName` 阻塞项，不改变 `resolveKnowledgeBasePromptContext(...)` 的对外调用与 KB 引用合同。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这轮先选测试面 `LargeClass`，避免把 `ChatServer`、`AgentRuntime` 这类高风险主链结构性迁移混进同一 slice。
- URL 导入合同仍覆盖成功、重复下载去重、坏链接、损坏 PDF、超时与拒连等既有行为，只是按职责拆分到独立 test class。
