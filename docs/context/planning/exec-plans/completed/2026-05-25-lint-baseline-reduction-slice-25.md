# Lint Baseline Reduction Slice 25

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 25 完成历史，记录本轮继续在 `frontend/webApp` 的 `Main.kt` 上做的一组低风险结构收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 25 条降到 24 条。
- `frontend/webApp` 已清空 `LoopWithTooManyJumpStatements` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 25: 在 `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt` 中抽出 `findMathBlockMatch(...)`，把数学块归一化循环从多次 `continue` / `break` 改成 helper 驱动，并顺手把 Markdown 链接与代码块包装的小循环改成 guard 形式，删除对应 1 条 `LoopWithTooManyJumpStatements` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 本轮保持 Markdown 数学、外链修饰和代码块包装的行为不变，只调整循环控制流形状，避免把复杂度重构和异常语义清理混在同一 slice。
- `frontend/webApp` 剩余项已经收敛到 `CyclomaticComplexMethod` 18、`ComplexCondition` 4，以及 `ApiClient.kt` 的 2 条签名级异常规则；下一步更适合继续拆条件表达式，而不是回头做大块格式化。
