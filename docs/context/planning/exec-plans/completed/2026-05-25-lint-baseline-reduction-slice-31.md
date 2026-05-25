# Lint Baseline Reduction Slice 31

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 31 完成历史，记录本轮继续在 `frontend/webApp` 的 `AudioDuplexScene.kt` 上做的一组低风险复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 16 条降到 15 条。
- `frontend/webApp` 的 `AudioDuplexScene.kt` 已清空 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 31: 在 `frontend/webApp/src/main/kotlin/com/silk/web/AudioDuplexScene.kt` 中抽出 transcript pane、空态、气泡、状态文案与拨号按钮 helper，并把状态颜色/按钮颜色和 transcript 解析提到独立 helper，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮一开始只看 detekt 是通过的，但 `nodeTest` 暴露了 Compose Web `Color` / `CSSColorValue` 的编译签名问题；最终把颜色 helper 改回字符串并在样式层显式包 `Color(...)` 后才完成收口。
- 音频双工页当前只做了结构收敛，没有改内联 JS 会话实现；后续若继续动这页，优先保持 helper 分层，不要把状态分支重新堆回 `AudioDuplexScene(...)`。
