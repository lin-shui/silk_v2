# Lint Baseline Reduction Slice 179

## Summary

这份归档保留 `lint-baseline-reduction` 的 Slice 179 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt` 上清掉最后 1 条 backend detekt baseline。活跃状态留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 将 `ChatServer` 内的大块职责拆到：
  - `backend/src/main/kotlin/com/silk/backend/ChatServerUrlSupport.kt`
  - `backend/src/main/kotlin/com/silk/backend/ChatServerAiSupport.kt`
  - `backend/src/main/kotlin/com/silk/backend/ChatServerRecallSupport.kt`
- `WebSocketConfig.kt` 只保留 `ChatServer` 门面、连接/广播主链和少量状态管理；URL 下载索引、AI/recall 流程、撤回/删除与群元数据查询移到 support 文件。
- 删除 `config/lint/detekt/backend.xml` 中 `WebSocketConfig.kt$ChatServer` 对应的 1 条 `LargeClass` baseline。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## Result

- `config/lint/detekt/backend.xml` 从 1 条降到 0 条。
- backend detekt baseline 清零；仓库当前所有 detekt baseline 均为 0。

## Notes

- 这轮只做职责拆分，没有改 WebSocket 路径、消息 payload、文件下载 URL、AI 调用入口、撤回/删除权限语义或 KB 注入合同。
- 拆分后暴露出的未接线 `executeDoctorDiagnosisUpdate(...)` 包装入口已一并删除，避免留下新的 `UnusedPrivateMember` 噪音。
