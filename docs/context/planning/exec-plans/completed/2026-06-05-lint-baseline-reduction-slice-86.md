# Lint Baseline Reduction Slice 86

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 86 完成历史，记录本轮清空 `frontend/shared` detekt baseline，并把 active plan 缩减为只保留 backend 剩余待办。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/frontend-shared.xml` 已清空，`<CurrentIssues/>` 为 0。
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/ChatClient.kt` 清掉了 `handleMessage(...)` 上的 `CyclomaticComplexMethod`、`NestedBlockDepth` 和历史消息缓冲复杂条件。
- `frontend/shared/src/androidMain/kotlin/com/silk/shared/PlatformWebSocket.android.kt` 与 `frontend/shared/src/desktopMain/kotlin/com/silk/shared/PlatformWebSocket.jvm.kt` 清掉了 `connect(...)` 的复杂度 baseline。
- `frontend/shared/src/commonMain/kotlin/com/silk/shared/i18n/StringsEn.kt` / `StringsZh.kt` 同步完成命名收敛，shared lint 不再保留类命名历史豁免。

## Completed Slice

1. Slice 86: 把 `ChatClient.handleMessage(...)` 拆成批量历史帧解析、单条解码、历史缓冲判定、Agent 状态更新和持久消息处理等 helper，保留原有消息合同和状态语义。
2. Slice 86: 把 Android / desktop `PlatformWebSocket.connect(...)` 收敛成“清理旧连接 + 建 URL + 打开会话 + 消费消息 + 失败处理”的 helper 组合，保留原有重连和断开语义。
3. Slice 86: 从 `frontend-shared.xml` 移除 7 条历史 baseline，并把 active plan 调整为仅跟踪 backend 余量。

## Validation

- `./gradlew :frontend:shared:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs :frontend:androidApp:compileDebugKotlin :frontend:desktopApp:compileKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- shared 侧的改动没有改变消息 payload、语言 key 或平台 `PlatformWebSocket` 对外签名；只是把旧逻辑下沉到 helper，降低 detekt 复杂度命中。
- 编译快检过程中保留了既有 warning（如 deprecated API、`expect/actual` Beta 提示）；这些不是本轮新增 lint 问题。
