# Lint Baseline Reduction Slice 41

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 41 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的消息卡片复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 4 条降到 3 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 清掉了 `AIMessageCard(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 41: 确认 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 的 `AIMessageCard(...)` 已经在前几轮 helper 拆分后自然回到 detekt 阈值内，本轮删除对应的 `CyclomaticComplexMethod` baseline，并移除代码上的复杂度 suppress，保留现有消息卡片渲染与选择模式行为不变。

## Validation

- `./gradlew :frontend:webApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一步验证了 `AIMessageCard(...)` 的 baseline 属于陈旧遗留，而不是仍需继续拆分的真实复杂度问题。
- `frontend/webApp` 现在只剩 `MessageItem(...)` 1 条复杂度 baseline，以及 `ApiClient.kt` 上 2 条异常语义 baseline；后续 web slice 应优先处理 `MessageItem(...)`。
