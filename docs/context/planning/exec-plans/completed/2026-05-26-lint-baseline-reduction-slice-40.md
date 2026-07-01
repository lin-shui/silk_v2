# Lint Baseline Reduction Slice 40

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 40 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的聊天页 host 编排复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 5 条降到 4 条。
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 清掉了 `ChatAppWithGroup(...)` 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 40: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中把 `ChatAppWithGroup(...)` 的 language/session effects、顶部 header、选择模式工具栏、常规操作工具栏、连接状态条，以及转发/成员邀请/上传输入等 host-overlay 分支拆成独立 helper，并把返回、导出、成员加载、转发目标加载等动作抽成小型 suspend helper，保留原有聊天页编排语义，同时删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一步仍然刻意没有摊开 `MessageItem(...)` 和 `AIMessageCard(...)` 的消息渲染细节，只处理聊天页外围 host / toolbar / overlay 编排。
- `ChatAppEffects(...)` 的清理逻辑改成独立 helper 后，仍保持切群/离开页面时的“标记已读”和自动滚动行为；本轮通过 `nodeTest + silkLint` 确认没有新增编译或 lint 噪音。
