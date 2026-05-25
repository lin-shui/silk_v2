# Lint Baseline Reduction Slice 27

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 27 完成历史，记录本轮继续在 `frontend/webApp` 的 `LoginScene.kt` 上做的一组低风险复杂度收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-webApp.xml` 从 20 条降到 19 条。
- `frontend/webApp` 的 `LoginScene.kt` 已清空 `CyclomaticComplexMethod` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 27: 在 `frontend/webApp/src/main/kotlin/com/silk/web/LoginScene.kt` 中抽出 `AuthField(...)`、`RegistrationFields(...)`、`AuthErrorMessage(...)`、`submitAuth(...)` 与 `authSubmitButtonText(...)`，把登录场景里的重复表单和提交分支拆成小 helper，删除对应 1 条 `CyclomaticComplexMethod` baseline。

## Validation

- `./gradlew :frontend:webApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:nodeTest --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮先后修了 `InputType` 泛型和 `onInput` 值类型两个编译签名问题，最终都已通过 `nodeTest` 与根 `silkLint` 复核，不需要回退拆分。
- `LoginScene` 现在更适合继续做局部 UI 复用；若后续再改认证流程，优先扩展这些 helper，而不是把逻辑重新塞回主 composable。
