# Slice 178

这份归档保留 `lint-baseline-reduction` 的 Slice 178 完成历史，记录本轮在 backend `AgentRuntime` 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `AgentRuntime.kt$AgentRuntime` 对应的 1 条 `LargeClass` baseline。
- `AgentRuntime.kt` 现在只保留对外门面、workflow/状态快照数据模型与 filesystem proxy API。
- 新增 `AgentRuntimeStateSupport.kt`、`AgentRuntimeCommandSupport.kt` 与 `AgentRuntimeAcpSupport.kt`，分别承接状态/persistence、命令与 prompt 编排、ACP question/permission/plan-review 交互，继续保持 `/use` / `@agent` / `/session` / `/compact` / AskUserQuestion / permission card / plan review / `_silk` bridge 调用路径与工作流持久化合同不变。

## Validation

- `git diff --check`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`

## Notes

- 这轮优先处理未出现在当前脏工作区里的 `AgentRuntime.kt`，继续避开已经有本地修改的 `WebSocketConfig.kt`。
- 拆分继续采用顶层 support 文件，没有把体积问题平移成新的大类；active plan 现在只剩 `WebSocketConfig.kt$ChatServer` 1 条 backend `LargeClass` baseline。
