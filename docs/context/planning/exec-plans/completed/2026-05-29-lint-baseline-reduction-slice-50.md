# Lint Baseline Reduction Slice 50

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 50 完成历史，记录本轮在 `frontend/androidApp` 的 `GroupListScreen.kt` 上做的 API 恢复路径收敛。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `frontend-androidApp.xml` 从 43 条降到 42 条。
- `frontend/androidApp/src/main/kotlin/com/silk/android/GroupListScreen.kt` 清掉了 `TooGenericExceptionCaught` baseline。
- 本轮没有改协议、HTTP payload 或跨端消息合同。

## Completed Slice

1. Slice 50: 删除 `GroupListScreen.kt` 里包裹 `ApiClient.getUserSettings(...)`、`getUserGroups(...)`、`createGroup(...)`、`joinGroup(...)` 的 generic `catch (Exception)`，改为直接依赖 `ApiClient.recoverApiCall(...)` 已提供的失败兜底；群组页继续保留“语言加载失败时沿用默认中文、群组加载失败时只打日志、创建/加入失败时写回 dialog 错误文案”的原有语义，同时删除对应 baseline。

## Validation

- `./gradlew :frontend:androidApp:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮中途一度留下了一个悬空的 `finally`，已在同轮修正并复跑 detekt 通过，没有留下新的 lint 噪音。
- `GroupListScreen.kt` 仍然保留复杂度 baseline，后续如果再回到这个文件，优先拆 `GroupMembersListDialog(...)` 或顶层 scene 编排，不要把异常恢复逻辑重新塞回去。
