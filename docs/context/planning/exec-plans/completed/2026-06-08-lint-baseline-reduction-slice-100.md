# Lint Baseline Reduction Slice 100

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 100 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/FileRoutes.kt` 上继续收敛 detekt 的低风险基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 108 条降到 105 条。
- `FileRoutes.kt` 删除了未使用的 `Json` 单例，去掉 1 条 `UnusedPrivateProperty` baseline。
- APK/HAP 版本字符串改成 `String.format(Locale.ROOT, ...)`，去掉 1 条 `ImplicitDefaultLocale` baseline。
- 文件删除后的搜索索引路径改成显式 debug 日志，不再保留占位 `TODO` 注释，去掉 1 条 `ForbiddenComment` baseline。

## Completed Slice

1. Slice 100: 清理 `FileRoutes.kt` 的 `UnusedPrivateProperty`、`ImplicitDefaultLocale` 与 `ForbiddenComment` 共 3 条 baseline。
2. Slice 100: 保持文件上传、下载、列举、APK/HAP 版本计算与删除响应语义不变，只收紧实现细节。
3. Slice 100: 没有尝试误删 `FileRoutes.kt` 里仍按文件聚合的 `TooGenericExceptionCaught` 或复杂度基线。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `FileRoutes.kt` 剩余 detekt 主要还是 `CyclomaticComplexMethod`、文件级聚合的 `TooGenericExceptionCaught`，以及 `indexFileToWeaviate(...)` 的复杂度；后续若回到这个文件，仍应按单函数慢拆。
- 这轮继续验证了“先做单点、低风险、可独立删 baseline 的项”比直接碰大路由/大 catch 更稳。
