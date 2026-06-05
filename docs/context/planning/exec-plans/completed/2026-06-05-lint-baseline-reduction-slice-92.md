# Lint Baseline Reduction Slice 92

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 92 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Routing.kt` 上清理一组未落地的占位代码。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 141 条降到 139 条。
- `Routing.kt` 的群组创建路由不再保留一段未使用的欢迎消息字符串拼装。
- 同时移除了 1 条 `UnusedPrivateProperty` 和 1 条 `ForbiddenComment` baseline。
- 本轮没有改群组创建 API 的返回体、建群成功条件，也没有引入新的自动欢迎消息行为。

## Completed Slice

1. Slice 92: 删除 `post("/groups/create")` 中未生效的 `welcomeMessage` 占位构造，保留现有 `GroupService.createGroup(...)` 返回逻辑不变。
2. Slice 92: 从 `backend.xml` 移除 `Routing.kt` 对应的 `UnusedPrivateProperty` 与 `ForbiddenComment` baseline。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- `Routing.kt` 仍有异常处理相关 baseline；本轮刻意不把建群路由和 WebSocket/代理链路的异常语义收敛混在同一 slice。
- active plan 继续保留 backend 其余 lint 待办，下一步仍应优先选单文件、单职责的小切片推进。
