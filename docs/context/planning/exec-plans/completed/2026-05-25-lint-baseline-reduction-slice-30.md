# Lint Baseline Reduction Slice 30

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 30 完成历史，记录本轮继续在 `frontend/webApp` 的 `KnowledgeBaseScene.kt` 上做的一组低风险复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 17 条降到 16 条。
- `frontend/webApp` 的 `KnowledgeBaseScene.kt` 已清空 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 30: 在 `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseScene.kt` 中抽出 topic sidebar、entry sidebar、editor pane、toolbar、空态与创建弹窗等 section/helper，并把知识库的加载、保存、导出、创建主题/条目动作外提成独立 helper，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮先只拆 UI section 还不够，detekt 仍把主 composable 里的本地动作函数一起计入复杂度；最终通过把保存、导出、创建等带分支动作外提到文件级 helper 才真正降到阈值以下。
- 知识库页现在已经稳定成“主题栏 + 条目栏 + 编辑器 + 弹窗 helper”的结构，后续继续加能力时优先扩展这些 section，不要把条件重新堆回 `KnowledgeBaseScene(...)`。
