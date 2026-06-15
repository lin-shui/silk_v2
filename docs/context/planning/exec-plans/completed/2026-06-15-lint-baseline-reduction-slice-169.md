# Lint Baseline Reduction Slice 169

这份归档保留 `lint-baseline-reduction` 的 Slice 169 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/agents/core/AgentRuntime.kt` 与 `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 上继续收敛 detekt baseline。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `AgentRuntime.kt$AgentRuntime$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- 删除 `config/lint/detekt/backend.xml` 中 `WebSocketConfig.kt$ChatServer$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline。
- `AgentRuntime` 的 workflow 持久化、seed 加载、ACP command、prompt、question/permission/plan review 和目录操作失败分支改为 `runAgentCatching` / `Result` 分支，继续透传 `CancellationException`。
- `ChatServer` 的历史/URL 缓存、成员查询、卡片回复、Weaviate best-effort 索引、URL 下载、AI job、历史召回、医生诊断和撤回/删除索引分支改为 `runChatCatching` / `Result` 分支，去掉文件内 broad-catch suppress。
- `backend.xml` 余量从 Slice 168 后的 12 条降到 10 条，剩余为 `Routing.kt` 复杂度 1 条、`Routing.kt` 文件级 `TooGenericExceptionCaught` 1 条与 `LargeClass` 8 条。

## 合同保持

- Agent workflow seed、session load/list/compact、prompt 失败、问题回答与权限审批仍返回原有用户可见文案；协程取消继续向外透传。
- WebSocket 消息持久化、卡片过期反馈、URL 下载状态、AI 取消日志、历史召回错误回写、撤回/删除后的 Weaviate best-effort 失败语义保持不变。

## 验证

- `./gradlew :backend:detekt`
- `./gradlew :backend:test`

## 备注

- 本轮没有处理 `Routing.kt` 文件级 broad catch，也没有开始 `LargeClass` 结构性拆分。
