# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Remaining Surfaces

- `config/lint/detekt/backend.xml`
- `backend/src/main/kotlin/com/silk/backend/`

已完成并归档：

- Android baseline 清零：见 [2026-06-02-lint-baseline-reduction-slice-85.md](../completed/2026-06-02-lint-baseline-reduction-slice-85.md) 与 [2026-06-05-android-lint-baseline-closeout.md](../completed/2026-06-05-android-lint-baseline-closeout.md)
- Web baseline 清零：见各 slice 归档，active plan 不再保留 Web 待办
- Desktop baseline 清零：active plan 不再保留 Desktop 待办
- Shared baseline 清零：见 [2026-06-05-lint-baseline-reduction-slice-86.md](../completed/2026-06-05-lint-baseline-reduction-slice-86.md)

## Guardrails

- 每个 lint 收敛 slice 必须同时包含源码修复和对应 baseline 删除。
- 不默认运行并提交全量 `./gradlew silkLintBaseline` 覆盖结果。
- 不把 ktlint/格式化大重排混进 detekt 收敛。
- 不为过 lint 改变协议字段、外部 API payload 或跨端消息合同；如需改名先用 `@SerialName` 保合同。
- 异常处理类规则不能机械替换；必须先判断是否可恢复、是否要透传 coroutine cancellation、是否需要记录日志或返回用户可理解错误。

## Validation

每一步至少运行：

- `./gradlew silkLint`
- `git diff --check`

按改动面追加最窄验证：

- backend 改动：`./gradlew :backend:detekt`、`./gradlew :backend:test`
- shared 改动：按受影响 consumer 追加编译或测试

## Current Snapshot

当前 detekt baseline 余量（2026-06-05，Slice 93 后）：

- `backend.xml`: 138
- `frontend-androidApp.xml`: 0
- `frontend-webApp.xml`: 0
- `frontend-shared.xml`: 0
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` detekt baseline 已清空，并于 2026-06-05 再次通过 `:frontend:androidApp:detekt`、`:frontend:androidApp:compileDebugKotlin` 与 `silkLint` 复验。
- `frontend/webApp` detekt baseline 已清空；后续 Web 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` detekt baseline 已清空；后续 Desktop 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` detekt baseline 已清空，并于 2026-06-05 通过 `:frontend:shared:detekt`、三端 consumer 编译与 `silkLint` 复验。
- `backend` 已无 `WildcardImport`、`UnusedPrivateMember`、`UnusedParameter`、`EmptyFunctionBlock`、`AsrRoutes.kt` 的 `SwallowedException`、`ChatHistoryBackupManager.kt` 的 `PrintStackTrace` / `SwallowedException`、`WeaviateClient.kt` 的 `PrintStackTrace`，以及 `WebSocketConfig.kt` 的 `ComplexCondition` / `PrintStackTrace` / `SwallowedException` baseline 和一条陈旧的 `TooGenericExceptionCaught(ex)` 残留；剩余主要是 `TooGenericExceptionCaught` 32、`CyclomaticComplexMethod` 29、`ConstructorParameterNaming` 16、`NestedBlockDepth` 11、`SwallowedException` 7。

## Current Status

- Slice 1-93 完成历史均已归档到 `docs/context/planning/exec-plans/completed/`。
- Android / Web / Desktop / Shared baseline 已清零；active plan 现在只保留 backend 的剩余 detekt 收敛。
- Android 侧既有 `JdkImageTransform` / `jlink` 环境阻塞仍未改变；这不影响 baseline 已清零这一事实。

## Next Slices

- Slice 94 候选：优先继续处理 `Routing.kt` 中单函数、单职责的剩余异常语义点。
- Slice 95 候选：如果继续 backend 复杂度，优先按单函数慢拆，不和异常语义 / import 收敛混在同一 slice。
- Slice 96 候选：如果 backend 异常语义继续推进，优先选单文件里的同类 catch / swallow 点做小批量收敛，不要横跨多个模块。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。

## Handoff Notes

- `frontend/androidApp` baseline 已清空；后续 Android 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/webApp` baseline 已清空；后续 Web 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` baseline 已清空；后续 Desktop 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` baseline 已清空；后续 shared 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- 如果回到 backend，优先选择单文件、单函数、单职责的收敛面，不要再按大范围机械清理切片。
- `WebSocketConfig.kt` 当前 broad-catch baseline 仍以文件级签名聚合；后续要拆异常语义时，先选边界最清晰的一组 catch，不要一次性移除整文件同签名 baseline。
