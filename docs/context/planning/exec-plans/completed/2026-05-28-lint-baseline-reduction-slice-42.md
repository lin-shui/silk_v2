# Lint Baseline Reduction Slice 42

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 42 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的消息渲染分发复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 3 条降到 2 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 清掉了 `MessageItem(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 42: 把 `MessageItem(...)` 收敛成仅负责 `renderMode` dispatch，把普通文本、文件消息、系统提示、卡片渲染、卡片回复摘要，以及选择态卡片壳层/下载动作等逻辑下沉到独立 helper，并删除对应的 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:compileKotlinJs --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `frontend/webApp` 里的 `Main.kt` 复杂度 baseline 已清零；web 侧只剩 `ApiClient.kt` 上 2 条签名级异常语义 baseline。
- 本轮顺手把 PDF 报告下载和普通文件下载的浏览器 `fetch + blob` 流程收敛到共享 helper，保持现有下载行为不变。
