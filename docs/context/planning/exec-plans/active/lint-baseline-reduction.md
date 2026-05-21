# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Affected Surfaces

- `config/lint/detekt/*.xml`
- `frontend/androidApp/src/main/kotlin/com/silk/android/`
- 后续候选：`backend/src/main/kotlin/com/silk/backend/`、`frontend/webApp/src/main/kotlin/com/silk/web/`

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

- `frontend/androidApp`：`./gradlew :frontend:androidApp:detekt`
- Android Kotlin 变更：`./gradlew :frontend:androidApp:compileDebugKotlin`
- Android 单测：`./gradlew :frontend:androidApp:testDebugUnitTest`
- backend 改动：`./gradlew :backend:test`
- web 改动：`./gradlew :frontend:webApp:nodeTest`

## Current Snapshot

当前 detekt baseline 余量（2026-05-21，Slice 22 后）：

- `backend.xml`: 186
- `frontend-androidApp.xml`: 57
- `frontend-webApp.xml`: 32
- `frontend-shared.xml`: 7
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` 已无 `WildcardImport` baseline，剩余主要是 `CyclomaticComplexMethod` 22、`TooGenericExceptionCaught` 11、`SwallowedException` 7。
- `frontend/webApp` 已无 `WildcardImport` 与 `UnusedParameter` baseline；`TooGenericExceptionCaught` 已从 11 条降到 3 条，剩余主要是 `CyclomaticComplexMethod` 18、`ComplexCondition` 4、`TooGenericExceptionCaught` 3。
- `backend` 已无 `WildcardImport` baseline；剩余主要是 `CyclomaticComplexMethod` 34、`TooGenericExceptionCaught` 33、`ConstructorParameterNaming` 16、`NestedBlockDepth` 14、`UnusedPrivateMember` 13。

## Current Status

- Slice 1-16 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slices-1-16.md](../completed/2026-05-21-lint-baseline-reduction-slices-1-16.md)。
- Slice 17-18 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slices-17-18.md](../completed/2026-05-21-lint-baseline-reduction-slices-17-18.md)。
- Slice 19 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-19.md](../completed/2026-05-21-lint-baseline-reduction-slice-19.md)。
- Slice 20 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-20.md](../completed/2026-05-21-lint-baseline-reduction-slice-20.md)。
- Slice 21 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-21.md](../completed/2026-05-21-lint-baseline-reduction-slice-21.md)。
- Slice 22 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-22.md](../completed/2026-05-21-lint-baseline-reduction-slice-22.md)。
- Android 侧已知 `jlink` / `JdkImageTransform` 阻塞保持不变；当前 active plan 继续优先选择不依赖该链路的窄 slice。

## Next Slices

- Slice 23 候选：回到 Android，优先处理 `ApiClient.kt`、`AudioDuplexScreen.kt`、`ChatScreen.kt` 的异常语义规则（`TooGenericExceptionCaught` / `SwallowedException`），但先避开会把整文件泛 catch 一次性摊开的重构。
- Slice 24 候选：继续做 `frontend/webApp` 的低风险异常语义收敛，优先看 `Main.kt` 里剩余的 `Throwable` / `dynamic` catch，或评估是否值得改 `ApiClient.kt` 的整组 helper。
- 如果继续留在 backend，下一轮不要再按“大范围机械清理”切；优先选单文件的 `UnusedPrivateMember` / `UnusedParameter` 或明确异常语义问题。
- 复杂度规则继续按单文件慢拆，不和异常语义 / import 收敛混在同一 slice。

## Handoff Notes

- `frontend/desktopApp` 已清空 detekt baseline；如果后续 desktop 再出现 lint，只接受“新增问题直接修源码”，不要再回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。
