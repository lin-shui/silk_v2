# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，逐步把 detekt baseline 转化为源码修复。原则是 baseline 只减不增，避免一次性大格式化或大重构，让每一步都能独立验证、独立 review。

## Initial Snapshot

- Lint 入口：`./gradlew silkLint`
- Baseline 目录：`config/lint/detekt/`
- 接入后 baseline 总量约 574 条：
  - `backend`: 278
  - `frontend/androidApp`: 113
  - `frontend/webApp`: 91
  - `frontend/desktopApp`: 58
  - `frontend/shared`: 34
- 最大类别：
  - `WildcardImport`: 206
  - `CyclomaticComplexMethod`: 88
  - `TooGenericExceptionCaught`: 67
  - `SwallowedException`: 27
  - `ConstructorParameterNaming`: 26

## Rules

- 每个 lint 收敛 PR 必须同时包含源码修复和对应 baseline 删除。
- 不默认运行并提交全量 `silkLintBaseline` 覆盖结果；只有专门维护 baseline 时才整体再生。
- 不把 ktlint/格式化大重排混进 detekt 收敛 PR。
- 不为过 lint 改变协议字段、外部 API payload 或跨端消息合同；需要改名时先用 `@SerialName` 保合同。
- 异常处理类规则不能机械替换；必须先判断是否可恢复、是否要透传 coroutine cancellation、是否需要记录日志或返回用户可理解错误。

## Recommended Order

1. 机械低风险规则：`WildcardImport`、`MayBeConst`、部分 `UseCheckOrError`。
2. 死代码规则：`UnusedPrivateMember`、`UnusedPrivateProperty`，再谨慎处理 `UnusedParameter`。
3. 异常语义规则：`TooGenericExceptionCaught`、`SwallowedException`、`PrintStackTrace`。
4. 序列化命名规则：`ConstructorParameterNaming`，用 `@SerialName` 保持 JSON 合同。
5. 复杂度规则：`CyclomaticComplexMethod`、`NestedBlockDepth`、`LargeClass`，按业务模块拆分并补足测试。

## Validation

每一步至少运行：

- `./gradlew silkLint`
- `git diff --check`

按改动面追加最窄验证：

- `frontend/shared` 合同或解析改动：三端文件合同 / parser 测试，按 `docs/context/quality/TEST_MATRIX.md`
- backend 改动：`./gradlew :backend:test`
- web 改动：`./gradlew :frontend:webApp:nodeTest`
- Android 改动：`./gradlew :frontend:androidApp:testDebugUnitTest`
- desktop 改动：`./gradlew :frontend:desktopApp:test`

## Next Slices

- Slice 1: 已完成。清理 `frontend/shared` 的 `WildcardImport` baseline，跑 `./gradlew silkLint` 和 shared 相关编译。
- Slice 2: 已完成。清理 backend 入口层的 `WildcardImport`，覆盖 `Application.kt`、`Routing.kt`、`routes/*`，跑 `./gradlew :backend:test`。
- Slice 3: 已完成。清理 `frontend/webApp` 纯 import 类问题，跑 `./gradlew :frontend:webApp:nodeTest`。
- Slice 4: 已完成。清理 `frontend/shared` 的明确私有未使用项。
- Slice 5: 已完成。收敛 shared WebSocket / ChatClient 异常处理规则，取消异常显式透传。
- Slice 6: 已完成。清理 `frontend/desktopApp` 的 `WildcardImport`，跑 desktop detekt / test / compile 与 `silkLint`。
- Slice 7: 候选。继续清理 `frontend/desktopApp` 的明确未使用私有成员 / 参数，避免跨模块跳跃过早进入复杂度规则。

## Progress Log

### 2026-05-11 Slice 1

- 清理 `frontend/shared` 的 21 条 `WildcardImport` baseline。
- `config/lint/detekt/frontend-shared.xml` 从 34 条降到 13 条。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 已验证：
  - `./gradlew silkLint --no-daemon --stacktrace --warning-mode all`
  - `./gradlew :frontend:shared:compileKotlinDesktop :frontend:shared:compileKotlinJs :frontend:shared:compileDebugKotlinAndroid --no-daemon --stacktrace --warning-mode all`

### 2026-05-12 Slice 2

- 清理 backend 入口层的 38 条 `WildcardImport` baseline，覆盖 `Application.kt`、`Routing.kt`、`routes/AsrRoutes.kt`、`routes/FileRoutes.kt`。
- `config/lint/detekt/backend.xml` 从 278 条降到 240 条；其中 `WildcardImport` 剩余 35 条，已不包含 backend 入口层文件。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 首次 `./gradlew :backend:test` 遇到 Kotlin 增量编译 stale class（`TrustedDirRecord.class` 缺失），清理 `backend/build` 后验证通过。
- 已验证：
  - `./gradlew silkLint`
  - `./gradlew :backend:clean :backend:test`
  - `./gradlew :backend:compileKotlin`
  - `./gradlew :backend:test`
  - `git diff --check`

### 2026-05-12 Slice 3

- 清理 `frontend/webApp` 的 37 条 `WildcardImport` baseline，覆盖 Web 主源码与 `MainTest.kt`。
- `config/lint/detekt/frontend-webApp.xml` 从 91 条降到 54 条；`frontend/webApp` 当前不再保留 `WildcardImport` baseline。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 首次 `./gradlew :frontend:webApp:nodeTest` 暴露少量显式 import 缺口，补齐后验证通过。
- 已验证：
  - `./gradlew :frontend:webApp:detekt --no-daemon --stacktrace`
  - `./gradlew :frontend:webApp:nodeTest --no-daemon --stacktrace`
  - `./gradlew silkLint --no-daemon --stacktrace`
  - `git diff --check`

### 2026-05-12 Slice 4

- 清理 `frontend/shared` JS 时间格式化里的 1 条明确未使用私有值。
- `config/lint/detekt/frontend-shared.xml` 从 13 条降到 12 条；当前已无 `UnusedPrivateProperty` baseline。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 已验证：
  - `./gradlew :frontend:shared:detekt :frontend:shared:compileKotlinJs --no-daemon --stacktrace`
  - `./gradlew silkLint --no-daemon --stacktrace`
  - `git diff --check`

### 2026-05-12 Slice 5

- 清理 `frontend/shared` 的 5 条异常处理 baseline：
  - `ChatClient.kt` 改为只捕获 JSON 序列化解析失败，不再泛捕发送 / 断开路径。
  - Android / JVM WebSocket 的 `CancellationException` 改为显式透传，连接 / 接收 / 发送 / 关闭只处理预期 IO 或状态异常。
  - JS WebSocket 改为捕获浏览器 API 抛出的 dynamic 错误并保留可读错误信息。
- `config/lint/detekt/frontend-shared.xml` 从 12 条降到 7 条；当前已无 `TooGenericExceptionCaught` / `SwallowedException` baseline。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 首次 `:frontend:shared:detekt` 暴露新增 `ThrowsCount` / `LoopWithTooManyJumpStatements`，已通过小 helper 和心跳发送结果变量收敛，没有新增 baseline。
- 已验证：
  - `./gradlew :frontend:shared:detekt --no-daemon --stacktrace`
  - `./gradlew :frontend:shared:detekt :frontend:shared:compileKotlinDesktop :frontend:shared:compileKotlinJs :frontend:shared:compileDebugKotlinAndroid --no-daemon --stacktrace`
  - `./gradlew silkLint --no-daemon --stacktrace`
  - `./gradlew :backend:compileKotlin --no-daemon --stacktrace`
  - `git diff --check`

### 2026-05-18 Slice 6

- 清理 `frontend/desktopApp` 的 28 条 `WildcardImport` baseline，覆盖 `ApiClient.kt`、`AppState.kt`、`GroupListScreen.kt`、`InvitationDialog.kt`、`LoginScreen.kt`、`Main.kt`、`MessageContextMenu.kt`、`SettingsScreen.kt`。
- `config/lint/detekt/frontend-desktopApp.xml` 从 58 条降到 30 条；`frontend/desktopApp` 当前已无 `WildcardImport` baseline。
- 没有运行全量 `silkLintBaseline` 再生，只删除已由源码修复覆盖的 baseline 项。
- 首次 `:frontend:desktopApp:compileKotlin` 命中 Kotlin 增量编译陈旧产物（`DesktopPdfReportContent.class` 缺失）；执行 `:frontend:desktopApp:clean` 后验证通过。
- 已验证：
  - `./gradlew :frontend:desktopApp:detekt`
  - `./gradlew :frontend:desktopApp:clean :frontend:desktopApp:test :frontend:desktopApp:compileKotlin silkLint`
  - `git diff --check`

## Handoff Notes

- 后续接力时先看本文件和 `config/lint/detekt/*.xml` 的剩余规则分布。
- `frontend/desktopApp` 已清空 `WildcardImport`；下一步优先看同模块内剩余 `UnusedPrivateProperty`、`UnusedPrivateMember`、`UnusedParameter`，能继续保持低风险切片。
- `frontend/shared/src/iosMain` 当前在 Gradle shared module 中暂时禁用，也不在根 detekt source set 中；本计划按当前 lint 覆盖面收敛 baseline，不把未启用 iOS 源码混进每一步。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。
- 完成一个 slice 后，在本文件记录已完成项、剩余数量和验证命令。
