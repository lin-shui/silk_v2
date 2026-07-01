# Lint Baseline Reduction Slice 104

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 104 完成历史，记录本轮在 backend 两个低风险持久化/配置入口上继续收敛异常语义基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 97 条降到 94 条。
- `UserSettingsRepository.kt` 的语言枚举回退从 `catch (e: Exception)` 收紧为 `catch (e: IllegalArgumentException)`，并保留 warning 日志。
- `WorkflowManager.kt` 的 workflow store 加载从 broad catch 收紧为 `SerializationException`、`IllegalArgumentException` 和 `IOException` 三类分层处理。

## Completed Slice

1. Slice 104: 清理 `UserSettingsRepository.kt` 的 1 条 `TooGenericExceptionCaught` 和 1 条 `SwallowedException` baseline。
2. Slice 104: 清理 `WorkflowManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
3. Slice 104: 保持用户设置默认语言回退、workflow store 读失败回退空 store 的既有行为不变，只收紧实现层异常语义。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- backend 剩余 baseline 已压到 94；`TooGenericExceptionCaught` 与 `CyclomaticComplexMethod` 现在并列为 29 条，是下一轮最值得继续收敛的主面。
- 这轮继续证明“单文件、同类问题、小范围收敛”仍然是推进 backend lint 的最低风险路径。
