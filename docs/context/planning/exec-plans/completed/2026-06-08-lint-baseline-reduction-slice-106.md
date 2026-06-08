# Lint Baseline Reduction Slice 106

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 106 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/trust/TrustedDirManager.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 92 条降到 91 条。
- `TrustedDirManager.kt` 不再用 `catch (e: Exception)` 吞并 store 读取或路径规范化失败；现已按反序列化、I/O、非法路径和权限失败分层处理。
- 新增坏 `trusted_dirs.json` 的回退测试，锁定“坏 store 不炸进程、回退空数据”的既有语义。

## Completed Slice

1. Slice 106: 清理 `TrustedDirManager.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 106: 保持 `load()` 在损坏 store 场景下回退空数据、`isTrusted()` 在路径规范化失败时退回原始路径比较，不改变对外 trust 判定合同。
3. Slice 106: 为坏 store 回退补一条测试，避免后续再用 broad catch 把同类语义打回 baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮继续沿用 backend lint 的“小文件、单规则、带测试锚点”策略，没有去碰 `Routing.kt` / `WebSocketConfig.kt` 这类聚合 broad-catch 面。
- `TrustedDirManager.kt` 已没有 detekt broad-catch baseline；后续若回到 trust 模块，应优先处理更高信号的调用侧或持久化边界，而不是再回填泛 catch。
