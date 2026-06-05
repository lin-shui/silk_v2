# Lint Baseline Reduction Slice 89

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 89 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ChatHistoryBackupManager.kt` 上完成一组低风险异常日志收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 147 条降到 145 条。
- `ChatHistoryBackupManager.kt` 清掉了 1 条 `PrintStackTrace` baseline 和 1 条 `SwallowedException` baseline。
- 备份/恢复失败现在统一通过结构化 logger 记录 throwable；备份元数据解析失败与过期备份删除失败也会附带原因日志。
- 本轮没有改备份目录结构、备份文件格式、恢复路径或清理策略。

## Completed Slice

1. Slice 89: 把 `backupGroupHistory(...)` 与 `restoreFromBackup(...)` 中的 `printStackTrace()` 改成结构化 `logger.error(..., e)`。
2. Slice 89: 让备份元数据解析失败与过期备份删除失败日志带上异常原因，避免继续吞掉异常上下文。
3. Slice 89: 从 `backend.xml` 移除 `ChatHistoryBackupManager.kt` 对应的 `PrintStackTrace` 与 `SwallowedException` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `ChatHistoryBackupManager.kt` 的 `TooGenericExceptionCaught` baseline 仍保留；本轮没有把多处文件 IO/序列化失败进一步细分为具体异常类型，避免把小 slice 膨胀成完整错误分层重构。
- 后续若继续切这个文件，优先考虑只拆 `restoreFromBackup(...)` 或 `backupGroupHistory(...)` 的 broad catch，不要把全部 IO 分支一次性改完。
