# Lint Baseline Reduction Slice 110

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 110 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/SilkAgent.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 88 条降到 87 条。
- `SilkAgent.indexMessageToSearch()` 不再使用 `catch (e: Exception)`；现改为 `runCatching`，并显式透传 `CancellationException`。
- Weaviate/索引失败时仍保持原有回退：记录日志并返回 `false`，不改变上层聊天链路的索引失败容忍语义。

## Completed Slice

1. Slice 110: 清理 `SilkAgent.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 110: 保持消息索引失败对调用方是“soft failure”的既有合同，不把搜索索引异常扩散成聊天主链路失败。
3. Slice 110: 让协程取消不再被误吞，避免在会话关闭或任务取消时把正常中断记录成普通异常。

## Validation

- `./gradlew :backend:test`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“小文件、单规则、单职责”的 backend lint 收敛策略，没有把 `SearchDrivenAgent.kt` 或 `Routing.kt` 这类更大的异常面混进来。
- 后续如果继续沿着 AI/搜索面推进，可优先评估 `ClaudeProcessClient.kt` 或 `SearchDrivenAgent.kt`，但要先确认不会把进程生命周期或外部搜索 fallback 的行为一起改大。
