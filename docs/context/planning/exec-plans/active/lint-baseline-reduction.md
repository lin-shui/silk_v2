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
- Slice 2: 清理 backend 入口层的 `WildcardImport`，优先 `Application.kt`、`routes/*`，跑 `./gradlew :backend:test`。
- Slice 3: 清理 `frontend/webApp` 纯 import 类问题，跑 `./gradlew :frontend:webApp:nodeTest`。
- Slice 4: 处理 `frontend/shared` 的明确私有未使用项。
- Slice 5: 专门评估 shared WebSocket 的异常处理规则，避免吞掉取消异常或隐藏连接失败。

## Progress Log

### 2026-05-11 Slice 1

- 清理 `frontend/shared` 的 21 条 `WildcardImport` baseline。
- `config/lint/detekt/frontend-shared.xml` 从 34 条降到 13 条。
- 没有运行全量 baseline 再生，只删除已由源码修复覆盖的 baseline 项。
- 已验证：
  - `./gradlew silkLint --no-daemon --stacktrace --warning-mode all`
  - `./gradlew :frontend:shared:compileKotlinDesktop :frontend:shared:compileKotlinJs :frontend:shared:compileDebugKotlinAndroid --no-daemon --stacktrace --warning-mode all`

## Handoff Notes

- 后续接力时先看本文件和 `config/lint/detekt/*.xml` 的剩余规则分布。
- `frontend/shared/src/iosMain` 当前在 Gradle shared module 中暂时禁用，也不在根 detekt source set 中；本计划按当前 lint 覆盖面收敛 baseline，不把未启用 iOS 源码混进每一步。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。
- 完成一个 slice 后，在本文件记录已完成项、剩余数量和验证命令。
