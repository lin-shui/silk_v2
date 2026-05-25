# Lint Baseline Reduction Slice 29

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 29 完成历史，记录本轮继续在 `frontend/webApp` 的 `SettingsScene.kt` 上做的一组低风险复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 18 条降到 17 条。
- `frontend/webApp` 的 `SettingsScene.kt` 已清空 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 29: 在 `frontend/webApp/src/main/kotlin/com/silk/web/SettingsScene.kt` 中抽出 `SettingsHeader(...)`、`LanguageSection(...)`、`DefaultInstructionSection(...)`、`CcSettingsSection(...)`、`SaveMessageBanner(...)` 与 `SettingsActionButtons(...)` 等 section/helper，把设置页主 composable 收敛为状态装配层，并把设置加载默认值逻辑提到独立 helper，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- Claude Code Bridge 状态刷新仍保留“10 秒后自动清空结果”的原有体验，这次改成通过实时 generation getter 判断，避免 section 拆分后误读旧状态值。
- 设置页现在已经分成“顶部栏 + 语言/默认指令表单 + CC Bridge 区块 + 底部操作按钮”几个稳定 section；后续继续加设置项时，优先扩展这些 helper，而不是把条件重新塞回 `SettingsScene(...)`。
