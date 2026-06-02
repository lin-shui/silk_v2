# Lint Baseline Reduction Slice 82

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 82 完成历史，记录本轮继续在 `frontend/androidApp` 的 `MainActivity.kt` 上处理 `SilkApp(...)` 复杂度。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 8 条降到 7 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/MainActivity.kt` 清掉了 `SilkApp(...)` 上 1 条 `CyclomaticComplexMethod` baseline。
- 本轮没有改 tab 定义、页面导航规则、版本更新状态源或下载/安装合同。

## Completed Slice

1. Slice 82: 把 `SilkApp(...)` 收敛成 app lifecycle observe、返回键保护、版本更新弹窗和 scene host 几层 helper，并把验证态、登录态和认证后主壳层拆开。
2. Slice 82: 从 `frontend-androidApp.xml` 移除 `MainActivity.kt` 对应的 `CyclomaticComplexMethod` baseline 1 条。

## Validation

- `./gradlew :frontend:androidApp:detekt`
- `./gradlew :frontend:androidApp:compileDebugKotlin`
- `git diff --check`

## Notes

- `SilkApp(...)` 现在只保留共享状态装配和顶层路由入口；effect、返回键、验证态和 tab-scene 分发都已下沉到 helper。后续继续改 Android 宿主页时，优先沿这些 helper 扩展，不要把登录/验证态、底部导航和 tab-scene 分发重新堆回顶层 composable。
