# Lint Baseline Reduction Slice 105

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 105 完成历史，记录本轮在 backend 两个本地文件读取入口上继续收敛异常语义基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 94 条降到 92 条。
- `EnvLoader.kt` 的 `.env` 读取回退从 `catch (e: Exception)` 收紧为 `IOException` 和 `SecurityException`。
- `KnowledgeBaseManager.kt` 的 KB store 加载从 broad catch 收紧为 `SerializationException`、`IllegalArgumentException` 和 `IOException` 三类分层处理。

## Completed Slice

1. Slice 105: 清理 `EnvLoader.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 105: 清理 `KnowledgeBaseManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
3. Slice 105: 保持 `.env` 缺失/读取失败时继续回退系统环境变量、KB store 读失败时继续回退空 store 的既有行为不变，只收紧实现层异常语义。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- backend 剩余 baseline 已压到 92；`CyclomaticComplexMethod` 29 和 `TooGenericExceptionCaught` 27 仍是最主要的两块。
- 这轮继续适合沿“文件存储/配置读取入口”的小切片策略推进，下一步可以优先看同类的 `Routing.kt` 单一路由族，或者继续找单文件 broad-catch 尾项。
