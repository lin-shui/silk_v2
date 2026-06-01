# Lint Baseline Reduction Slice 66

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 66 完成历史，记录本轮继续在 `backend` 的 `ChatHistoryManager.kt` 与 `WebSocketConfig.kt` 上做的未接线私有成员清理。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 174 条降到 170 条。
- `backend/src/main/kotlin/com/silk/backend/ChatHistoryManager.kt` 清掉了 1 条 `UnusedPrivateMember` baseline。
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 清掉了 2 条 `UnusedPrivateMember` baseline 和 1 条 `CyclomaticComplexMethod` baseline。
- backend baseline 里的 `UnusedPrivateMember` 已清零，剩余 backend lint 更集中到复杂度、异常语义和未使用参数。
- 本轮没有改 WebSocket 消息合同、历史持久化格式、路由或 Agent bridge 协议。

## Completed Slice

1. Slice 66: 删除 `ChatHistoryManager.kt` 里未再使用的 `getSessionDirLegacy(...)`，保持会话目录继续只走统一的标准化路径。
2. Slice 66: 删除 `WebSocketConfig.kt` 里已断开的旧智能诊断入口链，包括 `executeSmartDiagnosis(...)`、`checkPreviousDiagnosis(...)`、`executeQuickDiagnosisUpdate(...)` 与未接线的 `executeStepwiseAITask(...)`，避免继续为 dead code 保留复杂度与私有成员 baseline。
3. Slice 66: 同步删除 `WebSocketConfig.kt` 里未使用的 `checkIfUserIsHost(...)`，并从 `backend.xml` 移除对应 4 条 baseline。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮再次证明 backend 的 lint 收益主要来自“沿调用链确认代码是否仍接线”；后续再碰旧诊断或历史兼容 helper，先确认是否真的有入口引用，再决定重接还是删除。
